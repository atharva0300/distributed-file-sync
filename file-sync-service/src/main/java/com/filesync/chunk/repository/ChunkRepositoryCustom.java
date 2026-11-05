package com.filesync.chunk.repository;

public interface ChunkRepositoryCustom {

    /**
     * Inserts a chunk row as STAGING, or refreshes {@code updated_at} of an existing STAGING row
     * (so a retried upload is not mistaken for a stale one by the staging GC). Rows that are already
     * ACTIVE or DELETING are left untouched.
     *
     * @return 1 if a row was inserted or refreshed, 0 if the chunk is already ACTIVE/DELETING
     */
    int insertAsStaging(String hash, Long size, String storageKey);
}
