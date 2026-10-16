package com.filesync.conflict.service;

import com.filesync.common.exception.ConflictException;
import com.filesync.common.exception.ForbiddenException;
import com.filesync.common.exception.NotFoundException;
import com.filesync.conflict.dto.ConflictResolutionRequest;
import com.filesync.conflict.dto.ConflictResponse;
import com.filesync.conflict.entity.Conflict;
import com.filesync.conflict.repository.ConflictRepository;
import com.filesync.file.entity.FileEntry;
import com.filesync.file.repository.FileRepository;
import com.filesync.sync.entity.SyncOperation;
import com.filesync.sync.repository.SyncOperationRepository;
import com.filesync.upload.entity.UploadSession;
import com.filesync.upload.entity.UploadSessionChunk;
import com.filesync.upload.entity.UploadSessionStatus;
import com.filesync.upload.repository.UploadSessionChunkRepository;
import com.filesync.upload.repository.UploadSessionRepository;
import com.filesync.upload.service.CandidateValidator;
import com.filesync.version.entity.FileVersion;
import com.filesync.version.entity.FileVersionChunk;
import com.filesync.version.repository.FileVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Conflict resolution service.
 *
 * INVARIANT 11: Only one resolution can commit for a given conflict (via conditional update).
 * INVARIANT 12: KEEP_LOCAL and CREATE_CONFLICT_COPY validate candidate chunks via CandidateValidator.
 *
 * All three resolution paths are fully atomic within a single @Transactional:
 *   - Conflict locked FOR UPDATE (via findByIdAndStatusOpenForUpdate)
 *   - File locked FOR UPDATE
 *   - Version created + file updated + conflict marked RESOLVED — all in one commit
 *
 * No S3 reads happen inside this transaction (chunks were already stored).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConflictService {

    private final ConflictRepository conflictRepository;
    private final SyncOperationRepository syncOperationRepository;
    private final FileRepository fileRepository;
    private final FileVersionRepository fileVersionRepository;
    private final UploadSessionRepository uploadSessionRepository;
    private final UploadSessionChunkRepository uploadSessionChunkRepository;
    private final CandidateValidator candidateValidator;

    @Transactional(readOnly = true)
    public List<ConflictResponse> getConflicts(Long userId) {
        return conflictRepository.findByUserIdAndStatus(userId, "OPEN").stream()
                .map(ConflictResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * Resolve a conflict atomically.
     *
     * Concurrency:
     *   - Conflict locked FOR UPDATE prevents concurrent resolution of the same conflict.
     *   - resolveConflictConditionally returns 0 if already resolved → throws ConflictException.
     *
     * @param userId      authenticated user ID
     * @param operationId the SyncOperation that caused the conflict
     * @param request     resolution strategy + optional parameters
     */
    @Transactional
    public String resolveConflict(Long userId, Long operationId, ConflictResolutionRequest request) {
        SyncOperation op = syncOperationRepository.findById(operationId)
                .orElseThrow(() -> new NotFoundException("Operation not found: " + operationId));

        if (!op.getUser().getId().equals(userId)) {
            throw new ForbiddenException("Unauthorized");
        }

        // Find the open conflict for this operation
        Conflict initialConflict = conflictRepository.findByOperationIdAndFileIdAndStatus(
                        operationId, op.getFile().getId(), "OPEN")
                .orElseThrow(() -> new NotFoundException("Open conflict not found or already resolved"));

        // Lock conflict row FOR UPDATE — prevents concurrent resolution
        Conflict conflict = conflictRepository.findByIdAndStatusOpenForUpdate(initialConflict.getId())
                .orElseThrow(() -> new ConflictException("Conflict is being resolved by another process"));

        FileEntry file = op.getFile();

        String result = switch (request.strategy()) {
            case KEEP_SERVER -> resolveKeepServer(op, conflict);
            case KEEP_LOCAL  -> resolveKeepLocal(op, conflict, file, userId);
            case CREATE_CONFLICT_COPY -> resolveCreateConflictCopy(op, conflict, file, userId);
        };

        // Persist final status of the operation
        syncOperationRepository.save(op);
        return result;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Resolution strategies
    // ──────────────────────────────────────────────────────────────────────────

    private String resolveKeepServer(SyncOperation op, Conflict conflict) {
        // Discard client changes; server version already prevails
        op.setStatus("COMPLETED");
        int updated = conflictRepository.resolveConflictConditionally(conflict.getId(), "KEEP_SERVER");
        if (updated == 0) {
            throw new ConflictException("Conflict already resolved");
        }
        log.info("CONFLICT_RESOLVED_KEEP_SERVER: opId={}, conflictId={}", op.getId(), conflict.getId());
        return "Server version kept";
    }

    /**
     * KEEP_LOCAL: The client's version wins.
     * Creates a new FileVersion from the client's upload session and advances the file version.
     *
     * INVARIANT 12: Validates candidate chunks before creating the FileVersion.
     */
    private String resolveKeepLocal(SyncOperation op, Conflict conflict, FileEntry file, Long userId) {
        if (!"UPLOAD".equals(op.getOperationType())) {
            throw new ConflictException("KEEP_LOCAL is only supported for UPLOAD conflicts");
        }

        UploadSession session = getAndValidateSessionForResolution(op, userId);

        // Validate candidate chunks (CandidateValidator — structural validation only, no S3 I/O)
        List<UploadSessionChunk> sessionChunks = uploadSessionChunkRepository
                .findByUploadSessionIdOrderByChunkIndexAsc(session.getId());
        List<FileVersionChunk> validatedChunks = candidateValidator.validate(session, sessionChunks);

        // Lock the file row FOR UPDATE — consistent view, prevents concurrent mutation
        FileEntry lockedFile = fileRepository.findByIdAndUserIdForUpdate(file.getId(), userId)
                .orElseThrow(() -> new NotFoundException("File not found"));

        int targetVersion = lockedFile.getCurrentVersion() + 1;
        FileVersion conflictVersion = new FileVersion(
                lockedFile, targetVersion,
                session.getExpectedFileHash(), session.getExpectedFileSize(),
                op.getDevice(), "KEEP_LOCAL");
        fileVersionRepository.save(conflictVersion);

        for (FileVersionChunk fvc : validatedChunks) {
            fvc.setFileVersion(conflictVersion);
            conflictVersion.getChunks().add(fvc);
        }

        lockedFile.setCurrentVersion(targetVersion);
        lockedFile.setCurrentVersionId(conflictVersion);
        lockedFile.setFileHash(session.getExpectedFileHash());
        lockedFile.setFileSize(session.getExpectedFileSize());
        fileRepository.save(lockedFile);

        // Mark session COMPLETED
        int updated = uploadSessionRepository.transitionStatus(
                session.getId(), UploadSessionStatus.COMPLETED,
                List.of(UploadSessionStatus.INITIATED, UploadSessionStatus.UPLOADING));
        if (updated == 0) {
            // Session might be FINALIZING — still mark completed
            uploadSessionRepository.transitionStatus(
                    session.getId(), UploadSessionStatus.COMPLETED,
                    List.of(UploadSessionStatus.FINALIZING));
        }

        op.setStatus("COMPLETED");
        int conflictUpdated = conflictRepository.resolveConflictConditionally(conflict.getId(), "KEEP_LOCAL");
        if (conflictUpdated == 0) {
            throw new ConflictException("Conflict already resolved");
        }

        log.info("CONFLICT_RESOLVED_KEEP_LOCAL: opId={}, conflictId={}, newVersion={}", op.getId(), conflict.getId(), targetVersion);
        return "Local version kept (server advanced to v" + targetVersion + ")";
    }

    /**
     * CREATE_CONFLICT_COPY: Both server and client versions are preserved.
     * Creates a new file entry with the client's content beside the server's file.
     *
     * INVARIANT 12: Validates candidate chunks before creating the FileVersion.
     */
    private String resolveCreateConflictCopy(SyncOperation op, Conflict conflict, FileEntry file, Long userId) {
        if (!"UPLOAD".equals(op.getOperationType())) {
            throw new ConflictException("CREATE_CONFLICT_COPY is only supported for UPLOAD conflicts");
        }

        UploadSession session = getAndValidateSessionForResolution(op, userId);

        // Validate candidate chunks
        List<UploadSessionChunk> sessionChunks = uploadSessionChunkRepository
                .findByUploadSessionIdOrderByChunkIndexAsc(session.getId());
        List<FileVersionChunk> validatedChunks = candidateValidator.validate(session, sessionChunks);

        // Lock original file FOR UPDATE — prevents concurrent mutation to the original
        FileEntry lockedFile = fileRepository.findByIdAndUserIdForUpdate(file.getId(), userId)
                .orElseThrow(() -> new NotFoundException("File not found"));

        // Build conflict copy name with device suffix + unique suffix for disambiguation
        String conflictName = buildConflictCopyName(lockedFile.getName(), op);

        // Create conflict copy file entry
        FileEntry conflictCopy = new FileEntry(
                lockedFile.getUser(), lockedFile.getParentFolder(), conflictName, false);
        conflictCopy.setFileHash(session.getExpectedFileHash());
        conflictCopy.setCurrentVersion(1);
        conflictCopy.setFileSize(session.getExpectedFileSize());
        fileRepository.save(conflictCopy);

        // Create version for conflict copy
        FileVersion conflictVersion = new FileVersion(
                conflictCopy, 1,
                session.getExpectedFileHash(), session.getExpectedFileSize(),
                op.getDevice(), "CONFLICT_COPY");
        fileVersionRepository.save(conflictVersion);

        for (FileVersionChunk fvc : validatedChunks) {
            fvc.setFileVersion(conflictVersion);
            conflictVersion.getChunks().add(fvc);
        }

        conflictCopy.setCurrentVersionId(conflictVersion);
        fileRepository.save(conflictCopy);

        // Mark session COMPLETED
        uploadSessionRepository.transitionStatus(
                session.getId(), UploadSessionStatus.COMPLETED,
                List.of(UploadSessionStatus.INITIATED, UploadSessionStatus.UPLOADING,
                        UploadSessionStatus.FINALIZING));

        op.setStatus("COMPLETED");
        int conflictUpdated = conflictRepository.resolveConflictConditionally(conflict.getId(), "CREATE_CONFLICT_COPY");
        if (conflictUpdated == 0) {
            throw new ConflictException("Conflict already resolved");
        }

        log.info("CONFLICT_RESOLVED_CREATE_COPY: opId={}, conflictId={}, conflictCopyName={}",
                op.getId(), conflict.getId(), conflictName);
        return "Conflict copy created: " + conflictName;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Load and validate the upload session for conflict resolution.
     *
     * Part 11A fix: Rejects FINALIZING sessions (may be mid-commit by another thread).
     * The client should retry after the FINALIZING session resolves to COMPLETED/UPLOADING.
     */
    private UploadSession getAndValidateSessionForResolution(SyncOperation op, Long userId) {
        if (op.getUploadSessionId() == null) {
            throw new NotFoundException("UPLOAD operation has no uploadSessionId");
        }

        UploadSession session = uploadSessionRepository.findById(op.getUploadSessionId())
                .orElseThrow(() -> new NotFoundException("Upload session not found: " + op.getUploadSessionId()));

        // Ownership check
        if (!session.getFile().getUser().getId().equals(userId)) {
            throw new ForbiddenException("Upload session does not belong to the current user");
        }

        // Reject terminal states
        if (session.getStatus() == UploadSessionStatus.ABORTED
                || session.getStatus() == UploadSessionStatus.EXPIRED) {
            throw new ConflictException(
                "Upload session is in terminal state: " + session.getStatus()
                        + ". Create a new upload session to re-upload the file.");
        }

        // Reject FINALIZING — another thread may be completing it right now
        if (session.getStatus() == UploadSessionStatus.FINALIZING) {
            throw new ConflictException(
                "Upload session is currently being finalized. "
                        + "Please wait and retry; if it remains FINALIZING, create a new session.");
        }

        // COMPLETED: idempotent — only allowed if already resolved via this path
        // (should not normally happen; reject to be safe)
        if (session.getStatus() == UploadSessionStatus.COMPLETED) {
            throw new ConflictException("Upload session is already COMPLETED. Check if conflict was already resolved.");
        }

        return session;
    }

    /**
     * Builds a conflict copy name that includes the device name and a short UUID suffix.
     * Format: "{base} (conflict {deviceName} {short-uuid}){ext}"
     * Example: "report (conflict my-laptop abc12345).pdf"
     */
    private static String buildConflictCopyName(String originalName, SyncOperation op) {
        String deviceName = op.getDevice() != null && op.getDevice().getDeviceName() != null
                ? op.getDevice().getDeviceName().replaceAll("[^a-zA-Z0-9\\-_]", "-").toLowerCase()
                : "unknown";
        String shortId = UUID.randomUUID().toString().substring(0, 8);
        String suffix = " (conflict " + deviceName + " " + shortId + ")";

        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex > 0) {
            String base = originalName.substring(0, dotIndex);
            String ext = originalName.substring(dotIndex);
            return base + suffix + ext;
        }
        return originalName + suffix;
    }
}
