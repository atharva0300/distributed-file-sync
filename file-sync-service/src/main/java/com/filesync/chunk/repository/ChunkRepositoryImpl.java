package com.filesync.chunk.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ChunkRepositoryImpl implements ChunkRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Deduplication: the unique constraint on {@code hash} guarantees one row per content hash.
     * After this call the caller stores the bytes in storage-service and then moves the row
     * STAGING -> ACTIVE in the same transaction that creates the session reference. A crash in
     * between leaves a STAGING row that the staging GC removes after its grace period.
     */
    @Override
    @Transactional
    public int insertAsStaging(String hash, Long size, String storageKey) {
        return entityManager.createNativeQuery("""
                        INSERT INTO chunks (hash, size, storage_key, status, created_at, updated_at)
                        VALUES (:hash, :size, :storageKey, 'STAGING', NOW(), NOW())
                        ON CONFLICT (hash) DO UPDATE SET updated_at = NOW() WHERE chunks.status = 'STAGING'
                        """)
                .setParameter("hash", hash)
                .setParameter("size", size)
                .setParameter("storageKey", storageKey)
                .executeUpdate();
    }
}
