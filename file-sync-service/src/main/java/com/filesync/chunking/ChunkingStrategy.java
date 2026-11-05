package com.filesync.chunking;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Strategy interface for file chunking.
 *
 * <p>Implementations must be:</p>
 * <ul>
 *   <li><strong>Deterministic</strong> — same input bytes always produce the same list of {@link ChunkBoundary} objects.</li>
 *   <li><strong>Streaming</strong> — reads the input as a stream; must not load the entire file into memory.</li>
 *   <li><strong>Memory-efficient</strong> — suitable for files of arbitrary size (hundreds of MB to GB).</li>
 * </ul>
 *
 * <p>The caller is responsible for closing the {@link InputStream} after this method returns.</p>
 *
 * <p>After chunk boundaries are determined, each implementation computes the SHA-256
 * hash of the chunk bytes and embeds it in the returned {@link ChunkBoundary}. The hash
 * is the globally unique identity of the chunk content and is used for content-addressable
 * storage and deduplication.</p>
 */
public interface ChunkingStrategy {

    /**
     * Divide the input stream into chunks and return their boundaries.
     *
     * @param input     the file input stream, positioned at the beginning
     * @param totalSize the total byte count expected from the stream (used for pre-allocation hints only)
     * @return ordered list of chunk boundaries (index 0 = first chunk)
     * @throws IOException if the stream cannot be read
     */
    List<ChunkBoundary> chunk(InputStream input, long totalSize) throws IOException;
}
