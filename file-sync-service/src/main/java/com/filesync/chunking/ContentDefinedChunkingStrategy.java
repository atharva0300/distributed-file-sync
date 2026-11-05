package com.filesync.chunking;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Content-Defined Chunking (CDC) implementation using a Rabin fingerprint rolling hash.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Maintain a 64-byte sliding window over the input byte stream.</li>
 *   <li>At each byte position, compute a 64-bit Rabin polynomial fingerprint of the window.</li>
 *   <li>If {@code fingerprint & mask == 0} (where {@code mask = targetSize - 1}), declare a
 *       content-defined boundary — but only if the current chunk size is at least {@code minSize}.</li>
 *   <li>Force a boundary if the current chunk size reaches {@code maxSize}.</li>
 *   <li>SHA-256 each completed chunk and record a {@link ChunkBoundary}.</li>
 * </ol>
 *
 * <h2>Why Rabin fingerprinting?</h2>
 * <ul>
 *   <li>Deterministic: same file always produces the same boundaries.</li>
 *   <li>Rolling: each step updates the fingerprint in O(1) without re-reading the window.</li>
 *   <li>Well-studied: used in rsync, LBFS, and many production systems.</li>
 *   <li>Zero external dependencies: pure Java implementation.</li>
 * </ul>
 *
 * <h2>Polynomial</h2>
 * The irreducible polynomial {@code 0xbfe6b8a5bf378d83L} is a commonly used 64-bit
 * Rabin polynomial. The same polynomial is used by many production CDC systems.
 *
 * <h2>Boundary condition</h2>
 * {@code fingerprint & (targetSize - 1) == 0}
 * This produces chunks whose average size converges to {@code targetSize} in expectation,
 * because a random 64-bit value satisfies the condition with probability {@code 1/targetSize}.
 * The requirement that {@code targetSize} is a power of two is validated in the constructor.
 *
 * <h2>Memory usage</h2>
 * Reads in an 8 KB I/O buffer. Only the 64-byte window and the current chunk's SHA-256
 * context are held in memory at any time. The complete file is never loaded into memory.
 *
 * <h2>Thread safety</h2>
 * This class is <em>not</em> thread-safe. Use one instance per chunking operation,
 * or rely on {@link ChunkingStrategyFactory} which creates a new instance per call.
 */
@Slf4j
public class ContentDefinedChunkingStrategy implements ChunkingStrategy {

    // ── Rabin polynomial and window ───────────────────────────────────────────

    /**
     * 64-bit irreducible Rabin polynomial.
     * Value: 0xbfe6b8a5bf378d83 (standard production polynomial).
     */
    private static final long POLY = 0xbfe6b8a5bf378d83L;

    /**
     * Rolling window size in bytes. A 64-byte window is the de-facto standard
     * for Rabin CDC — large enough to produce stable fingerprints, small enough
     * to keep memory usage negligible.
     */
    static final int WINDOW_SIZE = 64;

    /**
     * Precomputed Rabin table for O(1) per-byte fingerprint updates.
     * {@code table[b]} gives the fingerprint contribution of byte {@code b} shifted
     * by {@code WINDOW_SIZE} positions into the past (the out-byte correction).
     */
    private static final long[] TABLE = buildTable();

    // ── Chunk size bounds ─────────────────────────────────────────────────────

    private final int minSize;
    private final int targetSize;
    private final int maxSize;

    /**
     * Boundary mask: {@code targetSize - 1}.
     * A boundary is declared when {@code fingerprint & mask == 0}.
     * Requires {@code targetSize} to be a power of two.
     */
    private final long mask;

    // ── I/O buffer ────────────────────────────────────────────────────────────

    private static final int IO_BUFFER_SIZE = 8 * 1024; // 8 KB

    /**
     * Creates a new CDC strategy with the specified size parameters.
     *
     * @param minSize    minimum chunk size in bytes (must be &gt; 0)
     * @param targetSize average/target chunk size in bytes (must be a power of two and &gt;= minSize)
     * @param maxSize    maximum chunk size in bytes (must be &gt;= targetSize)
     */
    public ContentDefinedChunkingStrategy(int minSize, int targetSize, int maxSize) {
        if (minSize <= 0) throw new IllegalArgumentException("minSize must be > 0");
        if (targetSize < minSize) throw new IllegalArgumentException("targetSize must be >= minSize");
        if (maxSize < targetSize) throw new IllegalArgumentException("maxSize must be >= targetSize");
        if ((targetSize & (targetSize - 1)) != 0) {
            throw new IllegalArgumentException(
                "targetSize must be a power of two for mask-based boundary detection; got " + targetSize);
        }
        this.minSize = minSize;
        this.targetSize = targetSize;
        this.maxSize = maxSize;
        this.mask = (long) targetSize - 1L;
    }

    // ── ChunkingStrategy implementation ──────────────────────────────────────

    /**
     * Divides the input stream into content-defined chunks.
     *
     * <p>For an empty stream (totalSize == 0), returns an empty list.</p>
     *
     * @param input     the file byte stream, positioned at the beginning
     * @param totalSize total bytes expected (used only for list pre-sizing)
     * @return ordered, non-overlapping {@link ChunkBoundary} objects covering the entire input
     * @throws IOException if the stream cannot be read
     */
    @Override
    public List<ChunkBoundary> chunk(InputStream input, long totalSize) throws IOException {
        if (totalSize == 0) {
            // Consume and confirm empty
            byte[] probe = new byte[1];
            int read = input.read(probe);
            if (read > 0) {
                log.warn("CDC: totalSize=0 but stream has data; processing anyway");
                return chunkNonEmpty(new java.io.SequenceInputStream(
                        new java.io.ByteArrayInputStream(probe, 0, read), input), totalSize);
            }
            return List.of();
        }
        return chunkNonEmpty(input, totalSize);
    }

    // ── Core chunking logic ───────────────────────────────────────────────────

    private List<ChunkBoundary> chunkNonEmpty(InputStream input, long totalSize) throws IOException {
        int estimatedChunks = Math.max(4, (int) (totalSize / targetSize) + 2);
        List<ChunkBoundary> boundaries = new ArrayList<>(estimatedChunks);

        // Rolling window state
        byte[] window = new byte[WINDOW_SIZE];
        int windowPos = 0;   // next slot to fill in the circular window
        long fingerprint = 0L;

        // Per-chunk SHA-256 digest
        MessageDigest digest = newSha256Digest();

        // I/O buffer
        byte[] ioBuffer = new byte[IO_BUFFER_SIZE];

        int chunkIndex = 0;
        long fileOffset = 0L;   // absolute position in the file
        long chunkStart = 0L;   // start offset of the current chunk
        int chunkLen = 0;       // bytes accumulated in the current chunk

        int n;
        while ((n = input.read(ioBuffer)) != -1) {
            for (int i = 0; i < n; i++) {
                byte b = ioBuffer[i];

                // ── Roll the window ──────────────────────────────────────────
                // The out-byte is the byte being evicted from the window.
                // Its contribution (shifted by WINDOW_SIZE positions) is removed
                // from the fingerprint using the precomputed table.
                byte outByte = window[windowPos];
                window[windowPos] = b;
                windowPos = (windowPos + 1) & (WINDOW_SIZE - 1); // fast modulo (WINDOW_SIZE is power of 2)

                // Update rolling fingerprint: shift left by 1 bit, XOR in new byte,
                // and remove the contribution of the evicted byte.
                fingerprint = (fingerprint << 1) ^ (b & 0xFFL) ^ TABLE[outByte & 0xFF];

                // ── Accumulate in SHA-256 ────────────────────────────────────
                digest.update(b);
                chunkLen++;
                fileOffset++;

                // ── Check boundary conditions ────────────────────────────────
                boolean contentBoundary = (chunkLen >= minSize) && ((fingerprint & mask) == 0L);
                boolean forcedBoundary = (chunkLen >= maxSize);

                if (contentBoundary || forcedBoundary) {
                    String hash = digestToHex(digest.digest());
                    boundaries.add(new ChunkBoundary(chunkIndex++, chunkStart, chunkLen, hash));

                    // Reset for next chunk
                    digest = newSha256Digest();
                    chunkStart = fileOffset;
                    chunkLen = 0;

                    // Reset window for a fresh start at the new chunk boundary.
                    // This is intentional: the window is NOT carried across chunk boundaries.
                    // Carrying the window would create a dependency between chunks,
                    // breaking the property that chunk content is self-contained.
                    java.util.Arrays.fill(window, (byte) 0);
                    windowPos = 0;
                    fingerprint = 0L;
                }
            }
        }

        // Flush the final partial chunk (every file ends here)
        if (chunkLen > 0) {
            String hash = digestToHex(digest.digest());
            boundaries.add(new ChunkBoundary(chunkIndex, chunkStart, chunkLen, hash));
        }

        log.debug("CDC completed: totalBytes={}, chunks={}, avgChunkSize={}",
                fileOffset, boundaries.size(),
                boundaries.isEmpty() ? 0 : fileOffset / boundaries.size());

        return boundaries;
    }

    // ── Rabin table construction ──────────────────────────────────────────────

    /**
     * Precompute a 256-entry table used to remove the contribution of an evicted
     * window byte from the rolling fingerprint in O(1).
     *
     * <p>Entry {@code table[b]} represents the Rabin fingerprint of a hypothetical
     * file that consists of byte {@code b} followed by {@code WINDOW_SIZE - 1}
     * zero bytes. When byte {@code b} is evicted from the left of the window, XOR-ing
     * {@code table[b]} into the fingerprint cancels its contribution.</p>
     */
    private static long[] buildTable() {
        long[] t = new long[256];
        for (int b = 0; b < 256; b++) {
            long v = b;
            // Multiply in GF(2^64) with irreducible polynomial POLY.
            // We shift v left by WINDOW_SIZE bit positions to represent
            // the eviction offset.
            for (int i = 0; i < WINDOW_SIZE; i++) {
                if ((v & 0x8000000000000000L) != 0) {
                    v = (v << 1) ^ POLY;
                } else {
                    v <<= 1;
                }
            }
            t[b] = v;
        }
        return t;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static String digestToHex(byte[] digest) {
        StringBuilder sb = new StringBuilder(64);
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
