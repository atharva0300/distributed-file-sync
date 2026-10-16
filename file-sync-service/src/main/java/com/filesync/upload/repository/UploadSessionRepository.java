package com.filesync.upload.repository;

import com.filesync.upload.entity.UploadSession;
import com.filesync.upload.entity.UploadSessionStatus;
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
public interface UploadSessionRepository extends JpaRepository<UploadSession, Long> {

    Optional<UploadSession> findByIdAndFileIdAndDeviceId(Long id, Long fileId, Long deviceId);

    boolean existsByFileIdAndStatusIn(Long fileId, List<UploadSessionStatus> statuses);

    @Query("SELECT COUNT(s) FROM UploadSession s WHERE s.file.user.id = :userId AND s.status IN (:statuses)")
    long countByUserIdAndStatusIn(@Param("userId") Long userId, @Param("statuses") List<UploadSessionStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM UploadSession s WHERE s.id = :id")
    Optional<UploadSession> findByIdForUpdate(@Param("id") Long id);

    /**
     * Conditional status transition — the core of the upload state machine.
     * Only transitions if the current status is in the allowed set.
     * Returns the number of rows updated (0 = precondition failed).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UploadSession s SET s.status = :targetStatus, s.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE s.id = :sessionId AND s.status IN (:currentStatuses)")
    int transitionStatus(@Param("sessionId") Long sessionId,
                         @Param("targetStatus") UploadSessionStatus targetStatus,
                         @Param("currentStatuses") List<UploadSessionStatus> currentStatuses);

    /**
     * Expire sessions that are INITIATED or UPLOADING and past their expiry time.
     * FINALIZING sessions are NOT expired here — they have a separate recovery path.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UploadSession s SET s.status = 'EXPIRED', s.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE s.expiresAt < CURRENT_TIMESTAMP AND s.status IN ('INITIATED', 'UPLOADING')")
    int expireOldSessions();

    /**
     * Find sessions stuck in FINALIZING longer than the cutoff.
     * These are sessions where the completion workflow crashed between Phase A and Phase C.
     */
    @Query("SELECT s FROM UploadSession s WHERE s.status = 'FINALIZING' AND s.updatedAt < :cutoff")
    List<UploadSession> findStuckFinalizingSessions(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Delete terminal sessions older than the cutoff date.
     * Uses updatedAt (when the session entered the terminal state) rather than createdAt.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM UploadSession s WHERE s.status IN ('COMPLETED', 'ABORTED', 'EXPIRED') " +
           "AND s.updatedAt < :cutoff")
    int deleteTerminalSessionsOlderThan(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Update the total expected chunk count for a session.
     *
     * <p>For CDC sessions, this is called after the client sends its delta manifest.
     * The total chunk count cannot be derived from file size alone for CDC chunking.</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UploadSession s SET s.totalChunks = :totalChunks, s.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE s.id = :sessionId")
    int updateTotalChunks(@Param("sessionId") Long sessionId, @Param("totalChunks") int totalChunks);
}
