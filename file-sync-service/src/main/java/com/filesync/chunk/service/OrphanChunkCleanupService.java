package com.filesync.chunk.service;

import com.filesync.chunk.entity.Chunk;
import com.filesync.chunk.entity.ChunkStatus;
import com.filesync.chunk.repository.ChunkRepository;
import com.filesync.client.StorageServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Orphan chunk garbage collection.
 *
 * Lifecycle: ACTIVE → DELETING → [physical delete] → [DB delete]
 *
 * Concurrency invariants:
 *   INVARIANT 1: A Chunk with an active UploadSessionChunk reference cannot be physically deleted.
 *   INVARIANT 2: A Chunk marked DELETING cannot receive a new upload-session reference.
 *
 * These invariants are enforced by locking the Chunk row FOR UPDATE in both GC and uploadChunk().
 *
 * Recovery for stuck DELETING chunks:
 *   - If physical delete succeeded but DB delete failed: re-check references, delete DB row
 *   - If physical delete failed: restore to ACTIVE for retry
 *   - If references appeared while DELETING: restore to ACTIVE (INVARIANT 1 was almost violated)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrphanChunkCleanupService {

    private final ChunkRepository chunkRepository;
    private final StorageServiceClient storageClient;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.sync.gc.batch-size:500}")
    private int batchSize;

    /** Run every hour: clean orphan chunks */
    @Scheduled(fixedDelay = 3600000)
    public void cleanupOrphanChunks() {
        LocalDateTime gracePeriod = LocalDateTime.now().minusHours(12);
        List<Long> orphanIds = chunkRepository.findOrphanChunkIds(gracePeriod, batchSize);

        if (orphanIds == null || orphanIds.isEmpty()) {
            return;
        }

        log.info("GC_CANDIDATE: foundCount={}", orphanIds.size());

        for (Long chunkId : orphanIds) {
            processOrphanCandidate(chunkId);
        }
    }

    /**
     * Run every 2 hours: recover chunks stuck in DELETING state.
     * Handles the case where physical delete succeeded but DB cleanup failed (or vice versa).
     */
    @Scheduled(fixedDelay = 7200000)
    public void recoverStuckDeletingChunks() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(2);
        List<Long> stuckIds = chunkRepository.findStuckDeletingChunkIds(cutoff, batchSize);

        if (stuckIds == null || stuckIds.isEmpty()) {
            return;
        }

        log.info("GC_DELETING_RECOVERY: foundStuckCount={}", stuckIds.size());

        for (Long chunkId : stuckIds) {
            recoverStuckDeletingChunk(chunkId);
        }
    }

    /**
     * Run every 1 hour: clean up stale STAGING chunks.
     * These are chunks where the DB row was inserted but the storage upload failed,
     * so they never transitioned to ACTIVE.
     */
    @Scheduled(fixedDelay = 3600000)
    public void cleanupStaleStagingChunks() {
        // 1 hour grace period to allow ongoing uploads to complete
        LocalDateTime gracePeriod = LocalDateTime.now().minusHours(1);
        List<Long> stagingIds = chunkRepository.findStaleStagingChunkIds(gracePeriod, batchSize);

        if (stagingIds == null || stagingIds.isEmpty()) {
            return;
        }

        log.info("GC_STAGING_CLEANUP: foundCount={}", stagingIds.size());

        for (Long chunkId : stagingIds) {
            String hash = transactionTemplate.execute(status -> {
                Chunk c = chunkRepository.findByIdForUpdate(chunkId).orElse(null);
                return (c != null && c.getStatus() == ChunkStatus.STAGING) ? c.getHash() : null;
            });

            if (hash != null) {
                try {
                    storageClient.deleteChunk(hash);
                } catch (Exception e) {
                    log.warn("GC_STAGING_S3_DELETE_ERROR: hash={}, error={}", hash, e.getMessage());
                }

                transactionTemplate.executeWithoutResult(status -> {
                    Chunk c = chunkRepository.findByIdForUpdate(chunkId).orElse(null);
                    if (c != null && c.getStatus() == ChunkStatus.STAGING) {
                        chunkRepository.delete(c);
                        log.info("GC_STAGING_DELETED: chunkId={}", chunkId);
                    }
                });
            }
        }
    }

    private void processOrphanCandidate(Long chunkId) {
        // Phase 1: Lock candidate, re-verify no references, mark DELETING
        Chunk markedChunk = transactionTemplate.execute(status -> {
            // Lock chunk FOR UPDATE — serializes against uploadChunk() creating references
            Chunk c = chunkRepository.findByIdAndStatusForUpdate(chunkId, ChunkStatus.ACTIVE).orElse(null);
            if (c == null) {
                return null; // Already DELETING or deleted by another GC run
            }

            // Re-check references under lock — INVARIANT 1
            long fileRefs = chunkRepository.countFileVersionReferences(chunkId);
            long sessionRefs = chunkRepository.countUploadSessionReferences(chunkId);

            if (fileRefs > 0 || sessionRefs > 0) {
                log.debug("GC_CANDIDATE_SKIPPED: chunkId={} has references (file={}, session={})",
                        chunkId, fileRefs, sessionRefs);
                return null; // Not an orphan
            }

            // Mark DELETING and record when (for stuck-chunk recovery)
            chunkRepository.updateStatusWithDeletingAt(chunkId, ChunkStatus.DELETING, LocalDateTime.now());
            c.setStatus(ChunkStatus.DELETING);
            log.info("GC_MARKED_DELETING: chunkId={}, hash={}", chunkId, c.getHash());
            return c;
        });

        if (markedChunk == null) {
            return;
        }

        // Phase 2: Physical delete outside transaction (no DB lock held)
        boolean physicalDeleted = false;
        try {
            storageClient.deleteChunk(markedChunk.getHash());
            physicalDeleted = true;
            log.info("GC_PHYSICAL_DELETE_SUCCESS: chunkId={}, hash={}", chunkId, markedChunk.getHash());
        } catch (Exception e) {
            log.error("GC_PHYSICAL_DELETE_FAILED: chunkId={}, hash={}, reverting to ACTIVE",
                    chunkId, markedChunk.getHash(), e);
            // Restore to ACTIVE for retry in next GC cycle
            transactionTemplate.executeWithoutResult(s -> {
                chunkRepository.updateStatus(chunkId, ChunkStatus.ACTIVE);
            });
        }

        // Phase 3: DB cleanup after successful physical delete
        if (physicalDeleted) {
            transactionTemplate.executeWithoutResult(status -> {
                // Re-lock and re-verify before deleting DB row
                Chunk c = chunkRepository.findByIdForUpdate(chunkId).orElse(null);
                if (c == null) {
                    return; // Already deleted by another process
                }
                if (c.getStatus() != ChunkStatus.DELETING) {
                    log.warn("GC_DB_DELETE_SKIP: chunkId={} status changed to {}", chunkId, c.getStatus());
                    return;
                }

                // Final reference check — if references appeared, do NOT delete
                long fileRefs = chunkRepository.countFileVersionReferences(chunkId);
                long sessionRefs = chunkRepository.countUploadSessionReferences(chunkId);

                if (fileRefs > 0 || sessionRefs > 0) {
                    // References appeared while DELETING — physical object is already gone
                    // This means INVARIANT 2 was violated (uploadChunk did not check DELETING properly)
                    // Restore to ACTIVE: the upload will fail when reading the missing chunk
                    log.error("GC_INVARIANT_VIOLATION: chunkId={} gained references while DELETING! Restoring ACTIVE.",
                            chunkId);
                    chunkRepository.updateStatus(chunkId, ChunkStatus.ACTIVE);
                } else {
                    chunkRepository.delete(c);
                    log.info("GC_DB_DELETE_SUCCESS: chunkId={}", chunkId);
                }
            });
        }
    }

    private void recoverStuckDeletingChunk(Long chunkId) {
        transactionTemplate.executeWithoutResult(status -> {
            Chunk c = chunkRepository.findByIdForUpdate(chunkId).orElse(null);
            if (c == null || c.getStatus() != ChunkStatus.DELETING) {
                return; // Already recovered or deleted by concurrent run
            }

            // Check references first
            long fileRefs = chunkRepository.countFileVersionReferences(chunkId);
            long sessionRefs = chunkRepository.countUploadSessionReferences(chunkId);

            if (fileRefs > 0 || sessionRefs > 0) {
                // References appeared — restore to ACTIVE
                chunkRepository.updateStatus(chunkId, ChunkStatus.ACTIVE);
                log.warn("GC_DELETING_RECOVERY_RESTORED: chunkId={} has references, restored to ACTIVE", chunkId);
                return;
            }

            // No references: try to delete DB row
            // Physical object may or may not exist — that's OK, storage service handles idempotent deletes
            try {
                storageClient.deleteChunk(c.getHash()); // idempotent — OK if already gone
            } catch (Exception e) {
                log.warn("GC_DELETING_RECOVERY_STORAGE_ERROR: chunkId={}, continuing with DB delete", chunkId, e);
            }
            chunkRepository.delete(c);
            log.info("GC_DELETING_RECOVERY_COMPLETED: chunkId={}", chunkId);
        });
    }
}
