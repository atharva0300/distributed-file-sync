package com.filesync.upload.dto;

/**
 * Response to {@code POST /api/upload/init}.
 *
 * <ul>
 *   <li>{@code sessionId}    — the upload session ID the client must supply in subsequent requests</li>
 *   <li>{@code chunkSize}    — the configured average/target chunk size (informational)</li>
 *   <li>{@code chunkingMode} — the chunking algorithm the client should apply: {@code "CDC"}</li>
 * </ul>
 *
 * <p>When {@code chunkingMode} is {@code "CDC"}, the client should apply content-defined chunking
 * using the target chunk size as the average, and then compute SHA-256 per chunk. The client sends
 * the resulting manifest to {@code POST /api/upload/delta} to discover which chunks are missing on
 * the server before uploading them.</p>
 */
public record UploadInitResponse(
        Long sessionId,
        Long chunkSize,
        String chunkingMode
) {
    /**
     * Backward-compatible constructor for internal callers that pre-date the chunkingMode field.
     */
    public UploadInitResponse(Long sessionId, Long chunkSize) {
        this(sessionId, chunkSize, "CDC");
    }
}
