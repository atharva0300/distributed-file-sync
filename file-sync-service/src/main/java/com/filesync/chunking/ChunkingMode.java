package com.filesync.chunking;

/**
 * Identifies the chunking algorithm used to produce a set of chunks.
 *
 * <ul>
 *   <li>{@code FIXED} — Legacy fixed-size chunking: every chunk except the last is exactly
 *       {@code chunkSize} bytes. Suffers from the boundary-shift problem.</li>
 *   <li>{@code CDC} — Content-Defined Chunking (Rabin fingerprint): chunk boundaries are
 *       determined by file content, so local insertions/deletions only affect a small local
 *       region and boundaries resynchronise naturally downstream.</li>
 * </ul>
 */
public enum ChunkingMode {
    FIXED,
    CDC
}
