package com.filesync.chunk.repository;

import com.filesync.chunk.entity.Chunk;
import com.filesync.chunk.entity.ChunkStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ChunkRepository extends JpaRepository<Chunk, Long>, ChunkRepositoryCustom {

    Optional<Chunk> findByHash(String hash);

    /**
     * Lock chunk FOR UPDATE by hash — used by uploadChunk() to serialize against GC.
     *
     * Concurrency invariant:
     *   - uploadChunk() locks the chunk before creating UploadSessionChunk reference
     *   - GC locks the chunk before marking DELETING
     *   - Only one can hold the lock at a time, preventing:
     *       - uploader adding reference to a DELETING chunk
     *       - GC deleting a chunk that has an active reference
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Chunk c WHERE c.hash = :hash")
    Optional<Chunk> findByHashForUpdate(@Param("hash") String hash);

    /**
     * Bounded orphan chunk query — finds ACTIVE chunks with no references.
     * Uses LIMIT to prevent unbounded memory usage.
     *
     * @param gracePeriod only consider chunks older than this time
     * @param limit       maximum number of candidate IDs to return per batch
     */
    @Query(value = """
            SELECT c.id FROM chunks c
            WHERE c.updated_at < :gracePeriod
              AND c.status = 'ACTIVE'
              AND NOT EXISTS (SELECT 1 FROM file_version_chunks fvc WHERE fvc.chunk_id = c.id)
              AND NOT EXISTS (SELECT 1 FROM upload_session_chunks usc WHERE usc.chunk_id = c.id)
            ORDER BY c.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findOrphanChunkIds(@Param("gracePeriod") LocalDateTime gracePeriod, @Param("limit") int limit);

    /**
     * Find stale STAGING chunks — these are chunks where the S3/storage upload was initiated
     * but never completed (e.g., storage-service crash, network failure).
     *
     * STAGING chunks older than the grace period are safe to delete because:
     *   - No ACTIVE reference can exist (STAGING never transitions if upload failed)
     *   - The client will retry the upload and create a fresh STAGING row
     *
     * @param gracePeriod only consider STAGING chunks older than this time (e.g., 1 hour ago)
     * @param limit       maximum number of candidates to return per batch
     */
    @Query(value = """
            SELECT c.id FROM chunks c
            WHERE c.updated_at < :gracePeriod
              AND c.status = 'STAGING'
            ORDER BY c.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findStaleStagingChunkIds(@Param("gracePeriod") LocalDateTime gracePeriod, @Param("limit") int limit);

    /**
     * Find chunks stuck in DELETING state for recovery.
     * These are chunks where physical delete may have succeeded but DB cleanup failed,
     * or where the GC worker crashed between the two steps.
     */
    @Query(value = """
            SELECT c.id FROM chunks c
            WHERE c.status = 'DELETING'
              AND c.deleting_at < :cutoff
            ORDER BY c.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findStuckDeletingChunkIds(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Chunk c WHERE c.id = :id AND c.status = :status")
    Optional<Chunk> findByIdAndStatusForUpdate(@Param("id") Long id, @Param("status") ChunkStatus status);

    /**
     * Takes a shared row lock (FOR SHARE) on the given chunks, in id order. Code that is about to
     * create a {@code file_version_chunks} reference calls this inside the same transaction: GC needs
     * an exclusive lock to mark a chunk DELETING, so it either waits for the referencing transaction
     * to commit (and then sees the reference) or has already marked the chunk, which the caller
     * detects by status.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT c FROM Chunk c WHERE c.id IN :ids ORDER BY c.id")
    List<Chunk> findAllByIdForShare(@Param("ids") java.util.Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Chunk c WHERE c.id = :id")
    Optional<Chunk> findByIdForUpdate(@Param("id") Long id);

    @Modifying
    @Query("UPDATE Chunk c SET c.status = :status WHERE c.id = :id")
    void updateStatus(@Param("id") Long id, @Param("status") ChunkStatus status);

    @Modifying
    @Query("UPDATE Chunk c SET c.status = :status, c.deletingAt = :deletingAt WHERE c.id = :id")
    void updateStatusWithDeletingAt(@Param("id") Long id, @Param("status") ChunkStatus status,
                                    @Param("deletingAt") LocalDateTime deletingAt);

    @Query("SELECT COUNT(fvc) FROM FileVersionChunk fvc WHERE fvc.chunk.id = :chunkId")
    long countFileVersionReferences(@Param("chunkId") Long chunkId);

    @Query("SELECT COUNT(usc) FROM UploadSessionChunk usc WHERE usc.chunk.id = :chunkId")
    long countUploadSessionReferences(@Param("chunkId") Long chunkId);
}
