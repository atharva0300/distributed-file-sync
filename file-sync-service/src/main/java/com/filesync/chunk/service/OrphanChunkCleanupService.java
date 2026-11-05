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
 * Lifecycle: ACTIVE|STAGING -> DELETING -> [physical delete] -> [DB row delete]
 *
 * Invariants:
 *   1. A chunk referenced by a file version or an upload session is never physically deleted.
 *      GC first takes an exclusive row lock, re-counts references under that lock and marks the chunk
 *      DELETING in a committed transaction; reference creators either hold a lock on the chunk row
 *      (uploadChunk / linkExistingChunk take FOR UPDATE, ChunkReferenceGuard takes FOR SHARE) or see
 *      DELETING and refuse to reference it.
 *   2. A chunk is never moved back to ACTIVE once deletion has begun, because its bytes may be gone.
 *      A chunk whose physical delete failed stays DELETING and is retried by recoverStuckDeletingChunks.
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

    /** Grace period before an unreferenced ACTIVE chunk is collected. */
    @Value("${app.sync.gc.orphan-grace-hours:12}")
    private long orphanGraceHours;

    /** Grace period before an abandoned STAGING chunk is collected. */
    @Value("${app.sync.gc.staging-grace-hours:1}")
    private long stagingGraceHours;

    /** A chunk may sit in DELETING this long before recovery takes over (covers an in-flight GC pass). */
    @Value("${app.sync.gc.deleting-recovery-minutes:15}")
    private long deletingRecoveryMinutes;

    @Scheduled(fixedDelayString = "${app.sync.gc.orphan-interval-ms:3600000}")
    public void cleanupOrphanChunks() {
        LocalDateTime grace = LocalDateTime.now().minusHours(orphanGraceHours);
        List<Long> orphanIds = chunkRepository.findOrphanChunkIds(grace, batchSize);
        if (orphanIds == null || orphanIds.isEmpty()) {
            return;
        }
        log.info("GC_CANDIDATE: foundCount={}", orphanIds.size());
        for (Long chunkId : orphanIds) {
            collect(chunkId, ChunkStatus.ACTIVE, grace);
        }
    }

    /** STAGING rows whose upload never finished (storage write or activation failed). */
    @Scheduled(fixedDelayString = "${app.sync.gc.staging-interval-ms:3600000}")
    public void cleanupStaleStagingChunks() {
        LocalDateTime grace = LocalDateTime.now().minusHours(stagingGraceHours);
        List<Long> stagingIds = chunkRepository.findStaleStagingChunkIds(grace, batchSize);
        if (stagingIds == null || stagingIds.isEmpty()) {
            return;
        }
        log.info("GC_STAGING_CLEANUP: foundCount={}", stagingIds.size());
        for (Long chunkId : stagingIds) {
            collect(chunkId, ChunkStatus.STAGING, grace);
        }
    }

    /** Finishes deletions that were interrupted (crash, storage outage) while the chunk was DELETING. */
    @Scheduled(fixedDelayString = "${app.sync.gc.deleting-recovery-interval-ms:600000}")
    public void recoverStuckDeletingChunks() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(deletingRecoveryMinutes);
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
     * Phase 1 (short tx): lock the row, re-verify state/age/references, mark DELETING.
     * Phase 2 (no tx): delete the bytes from storage-service (idempotent).
     * Phase 3 (short tx): delete the row if still DELETING and still unreferenced.
     */
    private void collect(Long chunkId, ChunkStatus expectedStatus, LocalDateTime grace) {
        String hash = transactionTemplate.execute(status -> {
            Chunk c = chunkRepository.findByIdAndStatusForUpdate(chunkId, expectedStatus).orElse(null);
            if (c == null) {
                return null; // state changed since the candidate query
            }
            if (c.getUpdatedAt() != null && !c.getUpdatedAt().isBefore(grace)) {
                return null; // touched again (e.g. a retried upload) — not stale any more
            }
            if (hasReferences(chunkId)) {
                log.debug("GC_CANDIDATE_SKIPPED: chunkId={} is referenced", chunkId);
                return null;
            }
            chunkRepository.updateStatusWithDeletingAt(chunkId, ChunkStatus.DELETING, LocalDateTime.now());
            log.info("GC_MARKED_DELETING: chunkId={}, hash={}, from={}", chunkId, c.getHash(), expectedStatus);
            return c.getHash();
        });
        if (hash == null) {
            return;
        }

        try {
            storageClient.deleteChunk(hash);
        } catch (Exception e) {
            // Stay DELETING; recoverStuckDeletingChunks retries. Never revert to ACTIVE: the bytes may be gone.
            log.error("GC_PHYSICAL_DELETE_FAILED: chunkId={}, hash={} (left DELETING for retry)", chunkId, hash, e);
            return;
        }

        deleteRowIfUnreferenced(chunkId);
    }

    private void recoverStuckDeletingChunk(Long chunkId) {
        String hash = transactionTemplate.execute(status -> {
            Chunk c = chunkRepository.findByIdForUpdate(chunkId).orElse(null);
            if (c == null || c.getStatus() != ChunkStatus.DELETING) {
                return null;
            }
            if (hasReferences(chunkId)) {
                // Should be impossible (see class javadoc). Only trust the chunk again if its bytes still exist.
                boolean bytesExist;
                try {
                    bytesExist = storageClient.chunkExists(c.getHash());
                } catch (Exception e) {
                    log.error("GC_DELETING_RECOVERY_STORAGE_UNREACHABLE: chunkId={}", chunkId, e);
                    return null;
                }
                if (bytesExist) {
                    chunkRepository.updateStatus(chunkId, ChunkStatus.ACTIVE);
                    log.warn("GC_DELETING_RECOVERY_RESTORED: chunkId={} is referenced and its bytes exist", chunkId);
                } else {
                    log.error("GC_INVARIANT_VIOLATION: chunkId={} hash={} is referenced but its bytes are gone; "
                            + "left DELETING, manual repair needed", chunkId, c.getHash());
                }
                return null;
            }
            return c.getHash();
        });
        if (hash == null) {
            return;
        }
        try {
            storageClient.deleteChunk(hash); // idempotent
        } catch (Exception e) {
            log.warn("GC_DELETING_RECOVERY_STORAGE_ERROR: chunkId={}, will retry", chunkId, e);
            return;
        }
        deleteRowIfUnreferenced(chunkId);
    }

    private void deleteRowIfUnreferenced(Long chunkId) {
        transactionTemplate.executeWithoutResult(status -> {
            Chunk c = chunkRepository.findByIdForUpdate(chunkId).orElse(null);
            if (c == null || c.getStatus() != ChunkStatus.DELETING) {
                return;
            }
            if (hasReferences(chunkId)) {
                log.error("GC_INVARIANT_VIOLATION: chunkId={} hash={} gained references while DELETING; "
                        + "left DELETING, manual repair needed", chunkId, c.getHash());
                return;
            }
            chunkRepository.delete(c);
            log.info("GC_DB_DELETE_SUCCESS: chunkId={}", chunkId);
        });
    }

    private boolean hasReferences(Long chunkId) {
        return chunkRepository.countFileVersionReferences(chunkId) > 0
                || chunkRepository.countUploadSessionReferences(chunkId) > 0;
    }
}
