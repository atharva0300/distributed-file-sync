package com.filesync.chunking;

/**
 * Describes a single content-defined (or fixed-size) chunk boundary.
 *
 * <ul>
 *   <li>{@code index}  — zero-based ordinal position of the chunk within the file version (used for ordering, not identity)</li>
 *   <li>{@code offset} — byte offset within the original file at which this chunk begins</li>
 *   <li>{@code size}   — number of bytes in this chunk</li>
 *   <li>{@code sha256Hash} — lowercase hex SHA-256 digest of the chunk's raw bytes (the content identity)</li>
 * </ul>
 *
 * <p>Chunk identity is the SHA-256 hash. Two chunks from different versions (or even
 * different files) that share the same hash represent identical content and will map to
 * a single physical S3 object.</p>
 */
public record ChunkBoundary(
        int index,
        long offset,
        int size,
        String sha256Hash
) {}
