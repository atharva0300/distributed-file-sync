package com.filesync.sync.repository;

import com.filesync.sync.entity.SyncOperation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SyncOperationRepository extends JpaRepository<SyncOperation, Long> {

    /**
     * Claims up to {@code limit} PENDING operations for processing.
     * Uses FOR UPDATE SKIP LOCKED to prevent two workers from processing the same operation.
     * Only picks operations whose available_at <= now (supports backoff scheduling).
     */
    @Query(value = """
            SELECT * FROM sync_operations
            WHERE status = :status
              AND available_at <= :now
            ORDER BY created_at ASC, id ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<SyncOperation> claimOperations(@Param("status") String status,
                                        @Param("now") LocalDateTime now,
                                        @Param("limit") int limit);

    /**
     * Find PROCESSING operations whose lease has expired.
     * Used by stale recovery to reclaim operations from crashed/slow workers.
     */
    @Query("SELECT s FROM SyncOperation s WHERE s.status = 'PROCESSING' AND s.leaseExpiresAt < :now")
    List<SyncOperation> findExpiredLeasedOperations(@Param("now") LocalDateTime now);

    List<SyncOperation> findByDeviceIdAndFileIdOrderByCreatedAtAsc(Long deviceId, Long fileId);

    Page<SyncOperation> findByUserId(Long userId, Pageable pageable);

    Page<SyncOperation> findByUserIdAndStatus(Long userId, String status, Pageable pageable);

    Optional<SyncOperation> findByUserIdAndClientOperationId(Long userId, String clientOperationId);

    /**
     * Conditional status update requiring valid lease token.
     * Returns 0 if lease token does not match (stale worker fencing).
     *
     * INVARIANT 6: Stale worker cannot update a SyncOperation after its lease token has been replaced.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SyncOperation s SET s.status = :newStatus, s.lockedAt = null, " +
           "s.lockedBy = null, s.leaseToken = null, s.leaseExpiresAt = null " +
           "WHERE s.id = :id AND s.leaseToken = :leaseToken AND s.status = 'PROCESSING'")
    int updateStatusConditionally(@Param("id") Long id,
                                  @Param("leaseToken") String leaseToken,
                                  @Param("newStatus") String newStatus);

    /**
     * Conditional failure update — sets new status (FAILED or PENDING for retry),
     * retry count, error message, and next available time.
     * Requires valid lease token for fencing.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SyncOperation s SET s.status = :newStatus, s.lockedAt = null, " +
           "s.lockedBy = null, s.leaseToken = null, s.leaseExpiresAt = null, " +
           "s.retryCount = :retryCount, s.lastError = :error, s.availableAt = :availableAt " +
           "WHERE s.id = :id AND s.leaseToken = :leaseToken")
    int updateFailureConditionally(@Param("id") Long id,
                                   @Param("leaseToken") String leaseToken,
                                   @Param("newStatus") String newStatus,
                                   @Param("retryCount") int retryCount,
                                   @Param("error") String error,
                                   @Param("availableAt") LocalDateTime availableAt);

    /**
     * Reclaim a stale operation back to PENDING for reprocessing.
     * Clears the old lease token so the previous worker cannot complete it.
     * A new token will be assigned when the next worker claims it.
     *
     * INVARIANT 6: Stale worker's updateStatusConditionally will affect 0 rows after this.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SyncOperation s SET s.status = 'PENDING', s.lockedAt = null, " +
           "s.lockedBy = null, s.leaseToken = null, s.leaseExpiresAt = null " +
           "WHERE s.id = :id AND s.status = 'PROCESSING'")
    int reclaimStaleOperation(@Param("id") Long id);

    /**
     * Heartbeat: renews lease_expires_at for a specific operation.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SyncOperation s SET s.leaseExpiresAt = :newExpiry " +
           "WHERE s.id = :id AND s.leaseToken = :leaseToken AND s.status = 'PROCESSING'")
    int renewLeaseConditionally(@Param("id") Long id, @Param("leaseToken") String leaseToken, @Param("newExpiry") LocalDateTime newExpiry);

    /**
     * Lock the operation for update and strictly verify the lease token and expiry.
     * Used to implement true fencing before performing a business mutation.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SyncOperation s WHERE s.id = :id AND s.leaseToken = :leaseToken AND s.leaseExpiresAt > CURRENT_TIMESTAMP")
    Optional<SyncOperation> findByIdAndLeaseForUpdate(@Param("id") Long id, @Param("leaseToken") String leaseToken);
}
