package com.filesync.sync.service;

import com.filesync.common.exception.ConflictException;
import com.filesync.common.exception.NotFoundException;
import com.filesync.device.entity.Device;
import com.filesync.device.service.DeviceService;
import com.filesync.file.entity.FileEntry;
import com.filesync.file.repository.FileRepository;
import com.filesync.file.service.FileService;
import com.filesync.sync.dto.QueueSyncOperationRequest;
import com.filesync.sync.dto.SyncOperationResponse;
import com.filesync.sync.entity.SyncOperation;
import com.filesync.sync.repository.SyncOperationRepository;
import com.filesync.upload.entity.UploadSession;
import com.filesync.upload.entity.UploadSessionStatus;
import com.filesync.upload.repository.UploadSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Sync operation queue service.
 *
 * Idempotency:
 *   Same (userId, clientOperationId, opType, fileId, payload, uploadSessionId)
 *       → return existing canonical SyncOperation
 *   Same clientOperationId but different content
 *       → 409 Conflict
 *   Two concurrent identical requests
 *       → exactly one DB row (via unique index + DataIntegrityViolationException recovery)
 *
 * INVARIANT 5: Two operations with same clientOperationId create at most one SyncOperation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncService {

    private final SyncOperationRepository operationRepository;
    private final FileRepository fileRepository;
    private final DeviceService deviceService;
    private final UploadSessionRepository uploadSessionRepository;

    @Transactional
    public SyncOperationResponse queueOperation(Long userId, QueueSyncOperationRequest request) {
        String opType = request.operation().toUpperCase();

        // ── Structural validation ──────────────────────────────────────────────
        if (!List.of("UPLOAD", "DELETE", "RENAME", "MOVE").contains(opType)) {
            throw new IllegalArgumentException("Unsupported operation type: " + opType);
        }
        if (request.fileId() == null) {
            throw new IllegalArgumentException("fileId is required for " + opType);
        }
        if (opType.equals("UPLOAD") && request.uploadSessionId() == null) {
            throw new IllegalArgumentException("uploadSessionId is required for UPLOAD operation");
        }
        if (opType.equals("RENAME")) {
            validateRenamePayload(request.payload());
        }
        if (opType.equals("MOVE")) {
            validateMovePayload(request.payload());
        }

        // ── Pre-enqueue idempotency check (fast path before DB write) ──────────
        if (request.clientOperationId() != null) {
            Optional<SyncOperation> existing = operationRepository.findByUserIdAndClientOperationId(
                    userId, request.clientOperationId());
            if (existing.isPresent()) {
                return handleExistingOperation(existing.get(), request, opType, userId);
            }
        }

        // ── Ownership and state validation ─────────────────────────────────────
        Device device = deviceService.getDeviceAndVerifyOwnership(request.deviceId(), userId);
        FileEntry file = fileRepository.findByIdAndUserIdAndDeletedFalse(request.fileId(), userId)
                .orElseThrow(() -> new NotFoundException("File not found or unauthorized: " + request.fileId()));

        if (opType.equals("UPLOAD")) {
            validateUploadSessionForQueuing(userId, request, file);
        }
        if (opType.equals("UPLOAD") && file.getCurrentVersion() > 0 && request.baseVersion() == null) {
            throw new IllegalArgumentException("baseVersion is required for existing file UPLOAD");
        }

        // ── Insert — handle concurrent duplicate via unique index violation ────
        SyncOperation op = new SyncOperation(
                device, device.getUser(), file,
                opType, request.payload(), request.baseVersion(),
                request.clientOperationId(), request.uploadSessionId());

        try {
            operationRepository.saveAndFlush(op);
        } catch (DataIntegrityViolationException e) {
            // Concurrent insert with same clientOperationId — re-fetch the winner
            if (request.clientOperationId() != null) {
                SyncOperation winner = operationRepository.findByUserIdAndClientOperationId(
                        userId, request.clientOperationId()).orElseThrow(
                        () -> new IllegalStateException("Idempotency race: no canonical operation found after conflict"));
                return SyncOperationResponse.from(winner);
            }
            throw new ConflictException("Duplicate operation: " + e.getMessage());
        }

        log.info("SYNC_OPERATION_QUEUED: userId={}, deviceId={}, operation={}, fileId={}, clientOpId={}",
                userId, request.deviceId(), opType, request.fileId(), request.clientOperationId());

        return SyncOperationResponse.from(op);
    }

    @Transactional(readOnly = true)
    public Page<SyncOperationResponse> getPendingOperations(Long userId, Pageable pageable) {
        return operationRepository.findByUserIdAndStatus(userId, "PENDING", pageable)
                .map(SyncOperationResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<SyncOperationResponse> getOperationsByStatus(Long userId, String status, Pageable pageable) {
        if (status == null || status.isBlank()) {
            return operationRepository.findByUserId(userId, pageable).map(SyncOperationResponse::from);
        }
        return operationRepository.findByUserIdAndStatus(userId, status, pageable)
                .map(SyncOperationResponse::from);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    private SyncOperationResponse handleExistingOperation(
            SyncOperation existing, QueueSyncOperationRequest request, String opType, Long userId) {
        // Verify the existing operation has the same content
        boolean matches = existing.getOperationType().equalsIgnoreCase(opType)
                && Objects.equals(
                        existing.getFile() != null ? existing.getFile().getId() : null,
                        request.fileId())
                && Objects.equals(existing.getPayload(), request.payload())
                && Objects.equals(existing.getUploadSessionId(), request.uploadSessionId());

        if (matches) {
            log.debug("SYNC_OPERATION_IDEMPOTENT: clientOpId={}", request.clientOperationId());
            return SyncOperationResponse.from(existing);
        } else {
            throw new ConflictException(
                "clientOperationId '" + request.clientOperationId() + "' already exists with different content");
        }
    }

    /**
     * Validates upload session integrity before enqueueing.
     * Ensures: session belongs to user, file matches, device matches, session not terminal.
     *
     * Part 8: Validate all relationships BEFORE enqueueing.
     */
    private void validateUploadSessionForQueuing(Long userId, QueueSyncOperationRequest request, FileEntry file) {
        UploadSession session = uploadSessionRepository.findById(request.uploadSessionId())
                .orElseThrow(() -> new NotFoundException("Upload session not found: " + request.uploadSessionId()));

        // Session must belong to the authenticated user
        if (!session.getFile().getUser().getId().equals(userId)) {
            throw new com.filesync.common.exception.ForbiddenException(
                "Upload session does not belong to the current user");
        }

        // Session file must match request fileId
        if (!session.getFile().getId().equals(request.fileId())) {
            throw new IllegalArgumentException(
                "Upload session fileId " + session.getFile().getId()
                        + " does not match request fileId " + request.fileId());
        }

        // Session device must match request deviceId
        if (!session.getDevice().getId().equals(request.deviceId())) {
            throw new IllegalArgumentException(
                "Upload session deviceId " + session.getDevice().getId()
                        + " does not match request deviceId " + request.deviceId());
        }

        // Session must not be in a terminal state
        if (session.getStatus() == UploadSessionStatus.COMPLETED
                || session.getStatus() == UploadSessionStatus.ABORTED
                || session.getStatus() == UploadSessionStatus.EXPIRED) {
            throw new ConflictException(
                "Upload session is in terminal state: " + session.getStatus()
                        + ". Create a new session.");
        }
    }

    private void validateRenamePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("RENAME requires a non-blank JSON payload with 'newName'");
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(payload);
            if (!node.has("newName") || node.get("newName").isNull() || node.get("newName").asText().isBlank()) {
                throw new IllegalArgumentException("RENAME payload must contain a non-blank 'newName' field");
            }
            FileService.validateName(node.get("newName").asText().trim());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("RENAME payload is not valid JSON: " + e.getMessage());
        }
    }

    private void validateMovePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("MOVE requires a non-blank JSON payload with 'targetFolderId'");
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(payload);
            if (!node.has("targetFolderId")) {
                throw new IllegalArgumentException("MOVE payload must contain 'targetFolderId' field (may be null for root)");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("MOVE payload is not valid JSON: " + e.getMessage());
        }
    }
}
