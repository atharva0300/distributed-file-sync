package com.filesync.upload.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.Valid;

import java.util.List;

/**
 * Request body for {@code POST /api/upload/delta}.
 *
 * <p>The client sends its complete chunk manifest for the new version of the file.
 * The server responds with the list of chunk hashes that do not yet exist on the
 * server (i.e., the chunks the client must upload).</p>
 *
 *   <li><strong>CDC mode:</strong> {@code chunkIndex} values are contiguous from 0, but the
 *       count is determined by the CDC algorithm, not by file size. Each entry SHOULD include
 *       {@code chunkSize} so the server can record per-chunk sizes. The mandatory
 *       {@code expectedFileSize} count-validation is skipped for CDC sessions.</li>
 * </ul>
 *
 * <h2>Delta identity (CDC)</h2>
 * <p>Delta determination is based on <strong>chunk hash identity</strong>, not chunk index.
 * The server computes: {@code missingHashes = clientHashes − serverHashes}. The index is
 * an ordering field only.</p>
 */
public record DeltaRequest(
        @Min(0) Integer baseVersion,
        @Min(0) Long expectedFileSize,
        @NotNull @Valid List<ChunkManifestEntry> clientChunks,
        // Required in practice: without it, the server never records totalChunks on the
        // upload session, and completeUpload() will later reject the session with
        // "CDC session totalChunks is not set". The client must call /upload/init first
        // and pass the resulting sessionId here.
        Long sessionId
) {
    /**
     * A single entry in the client's chunk manifest.
     *
     * <ul>
     *   <li>{@code chunkIndex} — zero-based position of the chunk in the file version (ordering, not identity)</li>
     *   <li>{@code chunkHash}  — lowercase hex SHA-256 hash of the chunk bytes (content identity)</li>
     *   <li>{@code chunkSize}  — byte count of this chunk</li>
     * </ul>
     */
    public record ChunkManifestEntry(
            @Min(0) int chunkIndex,
            @NotNull @Pattern(regexp = "^[a-f0-9]{64}$", message = "Must be a valid SHA-256 hash") String chunkHash,
            @Min(0) Long chunkSize
    ) {
        /**
         * Convenience constructor for tests and legacy callers that do not supply chunkSize.
         */
        public ChunkManifestEntry(int chunkIndex, String chunkHash) {
            this(chunkIndex, chunkHash, null);
        }
    }
}
