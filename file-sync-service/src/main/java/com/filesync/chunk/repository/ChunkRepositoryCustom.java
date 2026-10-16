package com.filesync.chunk.repository;

public interface ChunkRepositoryCustom {
    /**
     * Insert a chunk row with status=STAGING (if hash not already present).
     *
     * Deduplication contract:
     *   - If hash already exists (STAGING, ACTIVE, or DELETING): returns 0, no-op
     *   - If hash does not exist: inserts with status=STAGING, returns 1
     *
     * The caller is responsible for:
     *   1. Calling storage-service to physically store the data
     *   2. Transitioning STAGING → ACTIVE in the same transaction as UploadSessionChunk creation
     *   3. If storage fails: leave row as STAGING (GC will clean it up after grace period)
     *
     * @return number of rows inserted (0 or 1)
     */
    int insertAsStaging(String hash, Long size, String storageKey);

    /**
     * Legacy: Insert as ACTIVE directly (kept for backward compat in tests).
     * Prefer insertAsStaging() + activateChunk() for production flow.
     */
    int insertIfNotExists(String hash, Long size, String storageKey);
}
