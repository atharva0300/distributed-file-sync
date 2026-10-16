package com.filesync.file.dto;

import java.util.List;

/**
 * Describes the chunk layout of a file version so the client can reconstruct the file.
 *
 * <ul>
 *   <li>{@code fileName}   — file name (safe for Content-Disposition header)</li>
 *   <li>{@code fileSize}   — total byte count of the assembled file</li>
 *   <li>{@code chunkHashes} — ordered list of SHA-256 hashes (kept for backward compatibility)</li>
 *   <li>{@code chunks}     — ordered list of {@link ChunkInfo} objects with hash AND size per chunk;
 *                            use this for correct reassembly with variable-size (CDC) chunks</li>
 * </ul>
 *
 * <h2>File reconstruction</h2>
 * <pre>{@code
 * for (ChunkInfo c : manifest.chunks()) {
 *     byte[] chunkData = downloadChunk(c.hash());
 *     // chunkData.length == c.size()   (verified by storage service)
 *     outputStream.write(chunkData);
 * }
 * }</pre>
 * Do NOT assume {@code offset = index * fixedSize}. Always use the per-chunk
 * {@link ChunkInfo#size()} for correct reassembly when chunks may be variable-sized.
 */
public record DownloadManifest(
        String fileName,
        Long fileSize,
        List<String> chunkHashes,   // backward compat: ordered SHA-256 hashes
        List<ChunkInfo> chunks      // preferred: hash + size per chunk
) {
    /**
     * Per-chunk descriptor for file reassembly.
     *
     * @param hash SHA-256 hex hash of the chunk bytes (content identity and S3 key)
     * @param size byte count of this chunk
     */
    public record ChunkInfo(String hash, long size) {}

    /**
     * Backward-compatible constructor: accepts only hashes (no sizes).
     * Chunk sizes are set to -1 (unknown). Prefer the 4-arg constructor.
     */
    public DownloadManifest(String fileName, Long fileSize, List<String> chunkHashes) {
        this(fileName, fileSize, chunkHashes,
                chunkHashes == null ? List.of() :
                chunkHashes.stream().map(h -> new ChunkInfo(h, -1L)).toList());
    }
}
