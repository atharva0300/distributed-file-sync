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
     * Insert a chunk row with status=STAGING (if hash not already present).
     *
     * Deduplication: the DB unique constraint on `hash` ensures only ONE concurrent INSERT succeeds.
     * The loser gets 0 rows affected (PostgreSQL ON CONFLICT DO NOTHING) or catches a duplicate-key
     * exception (H2 fallback).
     *
     * After this call, the caller MUST:
     *   1. Store data in storage-service (S3/local)
     *   2. Transition STAGING → ACTIVE in the same transaction as UploadSessionChunk creation
     *
     * If the caller crashes between insert and storage, the row stays STAGING.
     * The STAGING GC job (OrphanChunkCleanupService) cleans up stale STAGING rows after grace period.
     */
    @Override
    @Transactional
    public int insertAsStaging(String hash, Long size, String storageKey) {
        String dialect = getDialect();
        boolean isH2 = dialect != null && dialect.contains("H2");

        if (!isH2) {
            // PostgreSQL: atomic upsert — only one concurrent INSERT wins due to unique(hash)
            String sql = """
                    INSERT INTO chunks (hash, size, storage_key, status, created_at)
                    VALUES (:hash, :size, :storageKey, 'STAGING', NOW())
                    ON CONFLICT (hash) DO NOTHING
                    """;
            return entityManager.createNativeQuery(sql)
                    .setParameter("hash", hash)
                    .setParameter("size", size)
                    .setParameter("storageKey", storageKey)
                    .executeUpdate();
        } else {
            // H2 fallback: check-then-insert (acceptable for tests, not production)
            String checkSql = "SELECT COUNT(*) FROM chunks WHERE hash = :hash";
            Number count = (Number) entityManager.createNativeQuery(checkSql)
                    .setParameter("hash", hash)
                    .getSingleResult();
            if (count.intValue() > 0) {
                return 0; // Already exists (STAGING or ACTIVE or DELETING)
            }
            try {
                String insertSql = """
                        INSERT INTO chunks (hash, size, storage_key, status, created_at)
                        VALUES (:hash, :size, :storageKey, 'STAGING', NOW())
                        """;
                return entityManager.createNativeQuery(insertSql)
                        .setParameter("hash", hash)
                        .setParameter("size", size)
                        .setParameter("storageKey", storageKey)
                        .executeUpdate();
            } catch (Exception ex) {
                return 0; // Concurrent insert in H2 test environment
            }
        }
    }

    /**
     * Legacy: Insert as ACTIVE directly.
     * Used in tests and backward-compat scenarios where STAGING lifecycle is not required.
     */
    @Override
    @Transactional
    public int insertIfNotExists(String hash, Long size, String storageKey) {
        String dialect = getDialect();
        boolean isH2 = dialect != null && dialect.contains("H2");

        if (!isH2) {
            String sql = """
                    INSERT INTO chunks (hash, size, storage_key, status, created_at)
                    VALUES (:hash, :size, :storageKey, 'ACTIVE', NOW())
                    ON CONFLICT (hash) DO NOTHING
                    """;
            return entityManager.createNativeQuery(sql)
                    .setParameter("hash", hash)
                    .setParameter("size", size)
                    .setParameter("storageKey", storageKey)
                    .executeUpdate();
        } else {
            String checkSql = "SELECT COUNT(*) FROM chunks WHERE hash = :hash";
            Number count = (Number) entityManager.createNativeQuery(checkSql)
                    .setParameter("hash", hash)
                    .getSingleResult();
            if (count.intValue() > 0) {
                return 0;
            }
            try {
                String insertSql = """
                        INSERT INTO chunks (hash, size, storage_key, status, created_at)
                        VALUES (:hash, :size, :storageKey, 'ACTIVE', NOW())
                        """;
                return entityManager.createNativeQuery(insertSql)
                        .setParameter("hash", hash)
                        .setParameter("size", size)
                        .setParameter("storageKey", storageKey)
                        .executeUpdate();
            } catch (Exception ex) {
                return 0;
            }
        }
    }

    private String getDialect() {
        Object dialect = entityManager.getEntityManagerFactory().getProperties().get("hibernate.dialect");
        return dialect != null ? dialect.toString() : null;
    }
}
