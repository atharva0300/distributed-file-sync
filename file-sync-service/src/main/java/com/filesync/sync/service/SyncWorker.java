package com.filesync.sync.service;

import com.filesync.conflict.entity.Conflict;
import com.filesync.conflict.repository.ConflictRepository;
import com.filesync.common.exception.SyncConflictException;
import com.filesync.file.entity.FileEntry;
import com.filesync.file.repository.FileRepository;
import com.filesync.file.service.FileService;
import com.filesync.sync.entity.SyncOperation;
import com.filesync.sync.repository.SyncOperationRepository;
import com.filesync.upload.service.UploadService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.UUID;

/**
 * Background sync worker.
 *
 * Phase A: Claim operations using FOR UPDATE SKIP LOCKED → no two workers process the same operation.
 *         Sets a new leaseToken and leaseExpiresAt on each claim.
 *
 * Phase B: Execute business logic.
 *         Metadata mutations (DELETE/RENAME/MOVE) use atomic OCC methods that verify baseVersion
 *         in the same transaction as the mutation — no TOCTOU race possible.
 *
 * Phase C: Conditional status update using leaseToken for fencing.
 *         A stale worker whose lease expired cannot update the operation.
 *
 * Recovery: Separate scheduler reclaims operations where lease_expires_at < now().
 *           Reclaim assigns a new leaseToken so the original worker's Phase C update affects 0 rows.
 *
 * INVARIANT 6: A stale worker cannot update a SyncOperation after its lease token has been replaced.
 * INVARIANT 10: Malformed queued operations never become COMPLETED.
 */
@Slf4j
@Service
public class SyncWorker {

    private final SyncOperationRepository syncOperationRepository;
    private final FileRepository fileRepository;
    private final ConflictRepository conflictRepository;
    private final TransactionTemplate transactionTemplate;
    private final FileService fileService;
    private final UploadService uploadService;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor workerExecutor;
    private final ThreadPoolTaskScheduler heartbeatScheduler;

    private final String workerId = UUID.randomUUID().toString();

    /** One permit per worker thread: operations are only claimed when a thread is free to run them. */
    private final Semaphore slots;

    @Value("${app.sync.background-worker.max-retries:3}")
    private int maxRetries;

    @Value("${app.sync.background-worker.lease-duration-minutes:10}")
    private int leaseDurationMinutes;

    @Value("${app.sync.background-worker.heartbeat-interval-ms:60000}")
    private long heartbeatIntervalMs;

    public SyncWorker(SyncOperationRepository syncOperationRepository,
                      FileRepository fileRepository,
                      ConflictRepository conflictRepository,
                      TransactionTemplate transactionTemplate,
                      FileService fileService,
                      UploadService uploadService,
                      ObjectMapper objectMapper,
                      @Qualifier("syncWorkerExecutor") ThreadPoolTaskExecutor workerExecutor,
                      @Qualifier("syncHeartbeatScheduler") ThreadPoolTaskScheduler heartbeatScheduler) {
        this.syncOperationRepository = syncOperationRepository;
        this.fileRepository = fileRepository;
        this.conflictRepository = conflictRepository;
        this.transactionTemplate = transactionTemplate;
        this.fileService = fileService;
        this.uploadService = uploadService;
        this.objectMapper = objectMapper;
        this.workerExecutor = workerExecutor;
        this.heartbeatScheduler = heartbeatScheduler;
        this.slots = new Semaphore(workerExecutor.getMaxPoolSize());
    }

    /**
     * Scheduled entry point: claims at most as many operations as there are free worker threads and
     * hands them to the worker pool, so this method returns quickly and never blocks the scheduler.
     */
    @Scheduled(fixedDelayString = "${app.sync.background-worker.fixed-delay-ms:2000}")
    public void dispatchPendingOperations() {
        int reserved = 0;
        while (reserved < BATCH_SIZE && slots.tryAcquire()) {
            reserved++;
        }
        if (reserved == 0) {
            return;
        }
        List<SyncOperation> claimed = List.of();
        try {
            claimed = claimOperations(reserved);
        } finally {
            slots.release(reserved - claimed.size());
        }
        for (SyncOperation op : claimed) {
            try {
                workerExecutor.execute(() -> {
                    try {
                        processSingleOperation(op);
                    } finally {
                        slots.release();
                    }
                });
            } catch (RuntimeException rejected) {
                // Only happens during shutdown; the lease expires and recoverStaleOperations re-queues it.
                slots.release();
                log.warn("WORKER_SUBMIT_REJECTED: opId={}", op.getId(), rejected);
            }
        }
    }

    /** Claims and processes one batch on the calling thread (used by tests and manual triggers). */
    public void processPendingOperations() {
        for (SyncOperation op : claimOperations(BATCH_SIZE)) {
            processSingleOperation(op);
        }
    }

    private static final int BATCH_SIZE = 10;

    /** Phase A: claim operations with FOR UPDATE SKIP LOCKED and a fresh lease token each. */
    private List<SyncOperation> claimOperations(int max) {
        List<SyncOperation> claimed = transactionTemplate.execute(status -> {
            List<SyncOperation> ops = syncOperationRepository.claimOperations("PENDING", LocalDateTime.now(), max);
            LocalDateTime leaseExpiry = LocalDateTime.now().plusMinutes(leaseDurationMinutes);
            for (SyncOperation op : ops) {
                op.setStatus("PROCESSING");
                op.setLockedAt(LocalDateTime.now());
                op.setLockedBy(workerId);
                op.setLeaseToken(UUID.randomUUID().toString());
                op.setLeaseExpiresAt(leaseExpiry);
                syncOperationRepository.save(op);
            }
            return ops;
        });
        if (claimed == null) {
            return List.of();
        }
        if (!claimed.isEmpty()) {
            log.info("WORKER_CLAIMED: workerId={}, count={}", workerId, claimed.size());
        }
        return claimed;
    }

    private void processSingleOperation(SyncOperation op) {
        // Set when a heartbeat finds the lease gone; checked inside the fenced transactions below.
        AtomicBoolean leaseLost = new AtomicBoolean(false);
        Runnable heartbeatTask = () -> {
            try {
                LocalDateTime newExpiry = LocalDateTime.now().plusMinutes(leaseDurationMinutes);
                int renewed = syncOperationRepository.renewLeaseConditionally(op.getId(), op.getLeaseToken(), newExpiry);
                if (renewed == 0) {
                    log.error("WORKER_LEASE_LOST_DURING_HEARTBEAT: workerId={}, opId={}", workerId, op.getId());
                    leaseLost.set(true);
                }
            } catch (Exception e) {
                log.error("Heartbeat error for opId={}", op.getId(), e);
            }
        };

        ScheduledFuture<?> heartbeatFuture = heartbeatScheduler.scheduleAtFixedRate(
                heartbeatTask, Instant.now().plusMillis(heartbeatIntervalMs), Duration.ofMillis(heartbeatIntervalMs));

        try {
            Runnable leaseVerifier = () -> {
                if (leaseLost.get()) {
                    throw new IllegalStateException("Lease lost — cannot process operation " + op.getId());
                }
                syncOperationRepository.findByIdAndLeaseForUpdate(op.getId(), op.getLeaseToken())
                        .orElseThrow(() -> new IllegalStateException("Lease lost or expired — cannot process operation " + op.getId()));
            };

            switch (op.getOperationType().toUpperCase()) {
                case "DELETE" -> executeDelete(op, leaseVerifier);
                case "RENAME" -> executeRename(op, leaseVerifier);
                case "MOVE" -> executeMove(op, leaseVerifier);
                case "UPLOAD" -> executeUpload(op, leaseVerifier);
                default -> throw new IllegalArgumentException("UNSUPPORTED operation: " + op.getOperationType());
            }

            // Phase C: Conditional completion (fenced)
            transactionTemplate.executeWithoutResult(status -> {
                SyncOperation fencedOp = syncOperationRepository.findByIdAndLeaseForUpdate(op.getId(), op.getLeaseToken())
                        .orElseThrow(() -> new IllegalStateException("Lease lost or expired"));
                fencedOp.setStatus("COMPLETED");
                fencedOp.setLockedAt(null);
                fencedOp.setLockedBy(null);
                fencedOp.setLeaseToken(null);
                fencedOp.setLeaseExpiresAt(null);
                syncOperationRepository.save(fencedOp);
                log.info("WORKER_COMPLETED: workerId={}, opId={}", workerId, op.getId());
            });

        } catch (SyncConflictException e) {
            // Business conflict (OCC baseVersion mismatch) → mark CONFLICT
            log.warn("WORKER_CONFLICT: workerId={}, opId={}, message={}", workerId, op.getId(), e.getMessage());
            markConflict(op, e.getFileId(), e.getClientBaseVersion(), e.getServerCurrentVersion());
        } catch (Exception e) {
            log.error("WORKER_FAILED: workerId={}, opId={}", workerId, op.getId(), e);
            handleFailure(op, e);
        } finally {
            heartbeatFuture.cancel(false);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Operation executors — all use atomic OCC methods (no TOCTOU)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * DELETE: Atomically verify baseVersion + soft-delete in ONE transaction.
     * INVARIANT 4: FileEntry version update only commits when current version == expected.
     */
    private void executeDelete(SyncOperation op, Runnable leaseVerifier) {
        if (op.getFile() == null) {
            throw new IllegalArgumentException("DELETE requires a fileId");
        }
        // deleteFileAtomically locks FileEntry FOR UPDATE, verifies baseVersion, and deletes atomically
        fileService.deleteFileAtomically(op.getUser().getId(), op.getFile().getId(), op.getBaseVersion(), leaseVerifier);
        log.info("SYNC_DELETE_SUCCESS: opId={}, fileId={}", op.getId(), op.getFile().getId());
    }

    /**
     * RENAME: Validate payload + atomically verify baseVersion + rename in ONE transaction.
     * INVARIANT 10: Malformed RENAME never becomes COMPLETED.
     */
    private void executeRename(SyncOperation op, Runnable leaseVerifier) {
        if (op.getFile() == null) {
            throw new IllegalArgumentException("RENAME requires a fileId");
        }
        if (op.getPayload() == null || op.getPayload().isBlank()) {
            throw new IllegalArgumentException("RENAME payload is missing");
        }

        String newName;
        try {
            JsonNode node = objectMapper.readTree(op.getPayload());
            if (!node.has("newName") || node.get("newName").isNull()) {
                throw new IllegalArgumentException("RENAME payload missing 'newName' field");
            }
            newName = node.get("newName").asText().trim();
            if (newName.isEmpty()) {
                throw new IllegalArgumentException("RENAME newName must not be blank");
            }
            // Validate filename rules
            FileService.validateName(newName);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("RENAME payload is not valid JSON: " + e.getMessage());
        }

        fileService.renameFileAtomically(op.getUser().getId(), op.getFile().getId(), op.getBaseVersion(), newName, leaseVerifier);
        log.info("SYNC_RENAME_SUCCESS: opId={}, fileId={}, newName={}", op.getId(), op.getFile().getId(), newName);
    }

    /**
     * MOVE: Validate payload + atomically verify baseVersion + move in ONE transaction.
     * INVARIANT 10: Malformed MOVE never becomes COMPLETED.
     */
    private void executeMove(SyncOperation op, Runnable leaseVerifier) {
        if (op.getFile() == null) {
            throw new IllegalArgumentException("MOVE requires a fileId");
        }
        if (op.getPayload() == null || op.getPayload().isBlank()) {
            throw new IllegalArgumentException("MOVE payload is missing");
        }

        Long targetFolderId;
        try {
            JsonNode node = objectMapper.readTree(op.getPayload());
            if (!node.has("targetFolderId")) {
                throw new IllegalArgumentException("MOVE payload missing 'targetFolderId' field");
            }
            targetFolderId = node.get("targetFolderId").isNull() ? null : node.get("targetFolderId").asLong();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("MOVE payload is not valid JSON: " + e.getMessage());
        }

        fileService.moveFileAtomically(op.getUser().getId(), op.getFile().getId(), op.getBaseVersion(), targetFolderId, leaseVerifier);
        log.info("SYNC_MOVE_SUCCESS: opId={}, fileId={}, targetFolderId={}", op.getId(), op.getFile().getId(), targetFolderId);
    }

    /**
     * UPLOAD: Complete the upload session.
     * completeUpload() handles its own OCC check atomically in Phase C.
     */
    private void executeUpload(SyncOperation op, Runnable leaseVerifier) {
        if (op.getUploadSessionId() == null) {
            throw new IllegalArgumentException("UPLOAD requires uploadSessionId");
        }
        uploadService.completeUpload(op.getUser().getId(), op.getUploadSessionId(), leaseVerifier);
        log.info("SYNC_UPLOAD_SUCCESS: opId={}, sessionId={}", op.getId(), op.getUploadSessionId());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Conflict and failure handling
    // ──────────────────────────────────────────────────────────────────────────

    private void markConflict(SyncOperation op, Long fileId, int clientBaseVersion, int serverVersion) {
        transactionTemplate.executeWithoutResult(status -> {
            int updated = syncOperationRepository.updateStatusConditionally(
                    op.getId(), op.getLeaseToken(), "CONFLICT");
            if (updated > 0) {
                SyncOperation currentOp = syncOperationRepository.findById(op.getId()).orElse(null);
                if (currentOp != null) {
                    currentOp.setLastError("Conflict: client base version " + clientBaseVersion
                            + " != server current version " + serverVersion);
                    syncOperationRepository.save(currentOp);

                    FileEntry file = fileRepository.findById(fileId).orElse(null);
                    if (file != null) {
                        Conflict conflict = new Conflict(currentOp, file, serverVersion, clientBaseVersion);
                        conflictRepository.save(conflict);
                    }
                    log.warn("WORKER_CONFLICT_RECORDED: workerId={}, opId={}", workerId, op.getId());
                }
            } else {
                log.warn("WORKER_CONFLICT_SKIPPED: workerId={}, opId={} (lease lost)", workerId, op.getId());
            }
        });
    }

    private void handleFailure(SyncOperation op, Exception e) {
        transactionTemplate.executeWithoutResult(status -> {
            SyncOperation reloadedOp = syncOperationRepository.findById(op.getId()).orElse(null);
            if (reloadedOp == null) return;

            // Fencing: verify we still own it via lease token
            if (op.getLeaseToken() == null || !op.getLeaseToken().equals(reloadedOp.getLeaseToken())) {
                log.warn("WORKER_LEASE_LOST_ON_FAILURE: workerId={}, opId={}", workerId, op.getId());
                return;
            }

            int nextRetryCount = reloadedOp.getRetryCount() + 1;
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.toString();
            // Truncate to avoid exceeding TEXT column limits
            if (errorMsg.length() > 2000) {
                errorMsg = errorMsg.substring(0, 2000) + "... (truncated)";
            }

            if (e instanceof IllegalArgumentException || nextRetryCount >= maxRetries) {
                int updated = syncOperationRepository.updateFailureConditionally(
                        reloadedOp.getId(), op.getLeaseToken(), "FAILED",
                        nextRetryCount, errorMsg, reloadedOp.getAvailableAt());
                if (updated > 0) {
                    log.error("WORKER_FAILED_PERMANENT: workerId={}, opId={}", workerId, reloadedOp.getId());
                }
            } else {
                int delaySeconds = (int) Math.pow(2, nextRetryCount); // Exponential backoff
                LocalDateTime nextAvailable = LocalDateTime.now().plusSeconds(delaySeconds);
                int updated = syncOperationRepository.updateFailureConditionally(
                        reloadedOp.getId(), op.getLeaseToken(), "PENDING",
                        nextRetryCount, errorMsg, nextAvailable);
                if (updated > 0) {
                    log.warn("WORKER_RETRY_SCHEDULED: workerId={}, opId={}, retryIn={}s",
                            workerId, reloadedOp.getId(), delaySeconds);
                }
            }
        });
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Stale lease recovery
    // Uses lease_expires_at instead of age-based detection.
    // INVARIANT 6: Stale worker cannot finalize after lease replaced.
    // ──────────────────────────────────────────────────────────────────────────

    @Scheduled(fixedDelayString = "${app.sync.background-worker.recovery-interval-ms:60000}")
    public void recoverStaleOperations() {
        List<SyncOperation> staleOps = syncOperationRepository.findExpiredLeasedOperations(LocalDateTime.now());
        for (SyncOperation op : staleOps) {
            // Reclaim with a new lease token — the old worker's lease token is now invalid
            int updated = syncOperationRepository.reclaimStaleOperation(op.getId());
            if (updated > 0) {
                log.warn("WORKER_STALE_RECLAIMED: opId={}, previousWorker={}", op.getId(), op.getLockedBy());
            }
        }
    }

}
