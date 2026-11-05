package com.filesync.upload.service;

import com.filesync.chunk.entity.Chunk;
import com.filesync.chunk.entity.ChunkStatus;
import com.filesync.chunk.repository.ChunkRepository;
import com.filesync.chunking.ChunkingStrategyFactory;
import com.filesync.client.StorageServiceClient;
import com.filesync.client.dto.ChunkStorageResponse;
import com.filesync.common.exception.NotFoundException;
import com.filesync.common.exception.SyncConflictException;
import com.filesync.common.util.HashUtil;
import com.filesync.device.entity.Device;
import com.filesync.device.repository.DeviceRepository;
import com.filesync.file.entity.FileEntry;
import com.filesync.file.repository.FileRepository;
import com.filesync.upload.dto.UploadInitResponse;
import com.filesync.upload.dto.UploadSessionResponse;
import com.filesync.upload.dto.DeltaRequest;
import com.filesync.upload.entity.UploadSession;
import com.filesync.upload.entity.UploadSessionChunk;
import com.filesync.upload.entity.UploadSessionStatus;
import com.filesync.upload.repository.UploadSessionChunkRepository;
import com.filesync.upload.repository.UploadSessionRepository;
import com.filesync.version.entity.FileVersion;
import com.filesync.version.entity.FileVersionChunk;
import com.filesync.version.repository.FileVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.filesync.websocket.SyncEvent;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UploadService {

    /** Content-defined chunking is the only supported chunking algorithm. */
    static final String CHUNKING_ALGORITHM = "CDC";

    private final UploadSessionRepository sessionRepository;
    private final UploadSessionChunkRepository sessionChunkRepository;
    private final ChunkRepository chunkRepository;
    private final StorageServiceClient storageClient;
    private final FileRepository fileRepository;
    private final DeviceRepository deviceRepository;
    private final FileVersionRepository versionRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final CandidateValidator candidateValidator;
    private final ChunkingStrategyFactory chunkingStrategyFactory;

    @Value("${app.sync.chunk-size:4194304}")
    private Long defaultChunkSize;

    @Value("${app.sync.session-expiry-rate:300000}")
    private long sessionExpiryRateMs;

    @Value("${app.upload.max-file-size:10737418240}") // Default 10GB
    private long maxFileSize;

    @Value("${app.upload.max-concurrent-sessions:5}")
    private int maxConcurrentSessions;

    public Long getDefaultChunkSize() {
        return defaultChunkSize;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // initUpload
    // ──────────────────────────────────────────────────────────────────────────

    @Transactional
    public UploadInitResponse initUpload(Long userId, Long fileId, Long deviceId, Integer baseVersion, Long expectedSize, String expectedHash) {
        // Validate expectedSize >= 0 (zero-byte files are allowed)
        if (expectedSize == null || expectedSize < 0) {
            throw new IllegalArgumentException("expectedFileSize must be >= 0");
        }
        if (expectedSize > maxFileSize) {
            throw new IllegalArgumentException("expectedFileSize exceeds maximum allowed size of " + maxFileSize + " bytes");
        }
        // Validate expectedHash is SHA-256 hex
        if (expectedHash == null || !expectedHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("expectedFileHash must be a 64-char lowercase SHA-256 hex string");
        }

        FileEntry file = fileRepository.findByIdAndUserId(fileId, userId)
                .orElseThrow(() -> new NotFoundException("File not found"));
        Device device = deviceRepository.findByIdAndUserId(deviceId, userId)
                .orElseThrow(() -> new NotFoundException("Device not found"));

        if (file.getCurrentVersion() > 0 && baseVersion == null) {
            throw new IllegalArgumentException("baseVersion is required for existing files");
        }
        if (baseVersion != null && baseVersion != file.getCurrentVersion()) {
            throw new SyncConflictException(fileId, baseVersion, file.getCurrentVersion());
        }

        long activeSessions = sessionRepository.countByUserIdAndStatusIn(userId,
                List.of(UploadSessionStatus.INITIATED, UploadSessionStatus.UPLOADING, UploadSessionStatus.FINALIZING));
        if (activeSessions >= maxConcurrentSessions) {
            throw new com.filesync.common.exception.ConflictException("Maximum concurrent upload sessions (" + maxConcurrentSessions + ") exceeded.");
        }

        String chunkingAlgorithm = CHUNKING_ALGORITHM;

        UploadSession session = new UploadSession(
                file, device, baseVersion,
                file.getCurrentVersion() + 1,
                expectedSize, expectedHash,
                (long) chunkingStrategyFactory.getTargetSize(),
                LocalDateTime.now().plusHours(24),
                chunkingAlgorithm);
        sessionRepository.save(session);

        log.info("UPLOAD_SESSION_INITIATED: sessionId={}, userId={}, fileId={}, expectedSize={}, chunkingAlgorithm={}",
                session.getId(), userId, fileId, expectedSize, chunkingAlgorithm);
        return new UploadInitResponse(session.getId(), (long) chunkingStrategyFactory.getTargetSize(), chunkingAlgorithm);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // uploadChunk
    //
    // STAGING Lifecycle (deduplication + orphan safety):
    //   1. Validate session active + chunk index bounds + hash format (outside TX)
    //   2. Verify SHA-256 of data (outside TX)
    //   3. Insert chunk as STAGING (inside TX) — DB unique constraint ensures only one succeeds
    //   4. Upload to storage-service (outside TX) — if this fails, chunk stays STAGING; GC cleans it
    //   5. Transition STAGING→ACTIVE + create UploadSessionChunk (inside TX, same transaction)
    //
    // Chunk size validation: each chunk must be in [minSize..maxSize] (the final chunk of
    // the file may be smaller than minSize); total size is validated at completeUpload.
    // ──────────────────────────────────────────────────────────────────────────

    public void uploadChunk(Long userId, Long sessionId, int chunkIndex, String clientHash, byte[] data) {
        // ── Step 1: Quick validation outside transaction (no locks held) ────────
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must be >= 0");
        }
        if (clientHash == null || !clientHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("chunkHash must be a 64-char lowercase SHA-256 hex string");
        }
        if (data == null) {
            throw new IllegalArgumentException("Chunk data must not be null");
        }

        // ── Step 2: Verify session is active; read session metadata ──────────────
        record SessionMeta(long chunkSize, String chunkingAlgorithm, Integer totalChunks,
                           int expectedChunks, boolean isLastChunk) {}
        final SessionMeta[] sessionMetaHolder = {null};

        transactionTemplate.executeWithoutResult(status -> {
            UploadSession s = sessionRepository.findByIdForUpdate(sessionId)
                    .orElseThrow(() -> new NotFoundException("Session not found"));
            if (!s.getFile().getUser().getId().equals(userId)) {
                throw new com.filesync.common.exception.ForbiddenException("Unauthorized session access");
            }
            if (s.getStatus() != UploadSessionStatus.INITIATED && s.getStatus() != UploadSessionStatus.UPLOADING) {
                throw new IllegalStateException("Session is not active: " + s.getStatus());
            }
            if (s.getExpiresAt().isBefore(LocalDateTime.now())) {
                throw new IllegalStateException("Session is expired");
            }

            long expectedSize = s.getExpectedFileSize();
            long sessionChunkSize = s.getChunkSize();

            int expectedChunks;
            boolean isLastChunk;

            // For CDC sessions, total chunk count is known only after delta is computed.
            // If totalChunks is set, use it for bounds checking; otherwise skip index bounds check.
            Integer totalChunks = s.getTotalChunks();
            if (totalChunks != null) {
                if (expectedSize > 0 && chunkIndex >= totalChunks) {
                    throw new IllegalArgumentException(
                        "chunkIndex " + chunkIndex + " out of range: CDC session expects " + totalChunks + " chunks");
                }
                expectedChunks = totalChunks;
                isLastChunk = (chunkIndex == totalChunks - 1);
            } else {
                // Delta not yet computed — no bounds checking possible
                expectedChunks = -1; // unknown
                isLastChunk = false; // will be determined at completeUpload
            }
            sessionMetaHolder[0] = new SessionMeta(sessionChunkSize, "CDC", totalChunks,
                    expectedChunks, isLastChunk);

            // Transition INITIATED → UPLOADING if needed
            if (s.getStatus() == UploadSessionStatus.INITIATED) {
                s.setStatus(UploadSessionStatus.UPLOADING);
                sessionRepository.save(s);
            }
        });

        // ── Step 3: Validate chunk size at upload time ────────────────────────────
        // For CDC sessions: chunk size must be in [minSize..maxSize], except the final
        // chunk of the file, which is legitimately allowed to be smaller than minSize
        // (the CDC algorithm forces a boundary at EOF regardless of accumulated size).
        // Without this floor, a client (or attacker) could upload arbitrarily many
        // 1-byte "chunks" with no server-side limit, defeating minSize-based boundary
        // semantics entirely.
        int maxSize = chunkingStrategyFactory.getMaxSize();
        int minSize = chunkingStrategyFactory.getMinSize();
        if (data.length > maxSize) {
            throw new IllegalArgumentException(
                "CDC chunk (index " + chunkIndex + ") size " + data.length
                + " exceeds maximum chunk size " + maxSize);
        }
        if (data.length == 0) {
            throw new IllegalArgumentException("Chunk data must not be empty");
        }
        boolean isLastChunk = sessionMetaHolder[0].isLastChunk();
        if (data.length < minSize && !isLastChunk) {
            throw new IllegalArgumentException(
                "CDC chunk (index " + chunkIndex + ") size " + data.length
                + " is below minimum chunk size " + minSize + " and is not the final chunk");
        }

        // ── Step 4: Verify SHA-256 outside transaction ────────────────────────────
        String serverHash = HashUtil.sha256Hex(data);
        if (!serverHash.equalsIgnoreCase(clientHash)) {
            throw new IllegalArgumentException("Chunk hash mismatch: client=" + clientHash + " server=" + serverHash);
        }

        // ── Step 5: Insert chunk as STAGING (inside short TX) ────────────────────
        final String placeholderKey = "staging/" + serverHash;
        transactionTemplate.executeWithoutResult(status -> {
            chunkRepository.insertAsStaging(serverHash, (long) data.length, placeholderKey);
        });

        // ── Step 6: Physical storage OUTSIDE transaction ──────────────────────────
        ChunkStorageResponse storageResponse;
        try {
            storageResponse = storageClient.storeChunk(serverHash, data);
        } catch (Exception storageEx) {
            log.error("CHUNK_STORAGE_FAILED: sessionId={}, chunkIndex={}, hash={}: {}",
                    sessionId, chunkIndex, serverHash, storageEx.getMessage());
            throw storageEx;
        }

        // ── Step 7: Activate chunk + create UploadSessionChunk (single TX) ──────
        transactionTemplate.executeWithoutResult(status -> {
            Chunk chunk = chunkRepository.findByHashForUpdate(serverHash)
                    .orElseThrow(() -> new IllegalStateException("Chunk not found after insert: " + serverHash));

            if (chunk.getStatus() == ChunkStatus.STAGING) {
                chunk.setStorageKey(storageResponse.storageKey());
                chunk.setStatus(ChunkStatus.ACTIVE);
                chunkRepository.save(chunk);
                log.debug("CHUNK_ACTIVATED: hash={}, storageKey={}", serverHash, storageResponse.storageKey());
            } else if (chunk.getStatus() == ChunkStatus.ACTIVE) {
                log.debug("CHUNK_DEDUPLICATED: hash={} already ACTIVE", serverHash);
            } else if (chunk.getStatus() == ChunkStatus.DELETING) {
                throw new com.filesync.common.exception.ConflictException(
                    "Chunk is being garbage collected. Please retry this chunk upload.");
            }

            if (chunk.getSize() != data.length) {
                throw new IllegalStateException(
                    "Chunk size mismatch: DB has " + chunk.getSize() + " but data is " + data.length);
            }

            UploadSession currentSession = sessionRepository.findByIdForUpdate(sessionId).orElseThrow();
            if (currentSession.getStatus() != UploadSessionStatus.INITIATED
                    && currentSession.getStatus() != UploadSessionStatus.UPLOADING) {
                throw new IllegalStateException("Session is no longer active: " + currentSession.getStatus());
            }

            // Idempotent chunk mapping — same hash+index = OK; different hash for same index = conflict
            Optional<UploadSessionChunk> existing = sessionChunkRepository
                    .findByUploadSessionIdAndChunkIndex(sessionId, chunkIndex);
            if (existing.isPresent()) {
                UploadSessionChunk existingChunk = existing.get();
                if (existingChunk.getChunk().getId().equals(chunk.getId())
                        && existingChunk.getChunkSize() == data.length) {
                    log.debug("UPLOAD_CHUNK_DUPLICATE_IDEMPOTENT: sessionId={}, chunkIndex={}", sessionId, chunkIndex);
                    return;
                }
                throw new com.filesync.common.exception.ConflictException(
                    "Chunk index " + chunkIndex + " already uploaded with different content");
            }

            UploadSessionChunk sessionChunk = new UploadSessionChunk(currentSession, chunkIndex, chunk, (long) data.length);
            sessionChunk.setStatus("UPLOADED");
            sessionChunkRepository.save(sessionChunk);

            log.debug("UPLOAD_CHUNK_ACCEPTED: sessionId={}, chunkIndex={}, hash={}", sessionId, chunkIndex, serverHash);
        });
    }

    // ──────────────────────────────────────────────────────────────────────────
    // linkExistingChunk
    //
    // CDC delta sync (computeDelta) tells the client which chunk hashes are
    // MISSING on the server; every other index in the file's manifest already
    // matches a chunk the server has (from this file's previous version, or from
    // dedup against a completely different file). Those indices still need an
    // UploadSessionChunk row before completeUpload() will accept the session
    // (CandidateValidator requires every index 0..N-1 to be present) — but the
    // client should never have to re-transmit bytes it knows the server already
    // has, or delta sync saves nothing.
    //
    // This endpoint is the safe replacement for the old confirmChunk() flow.
    // Unlike that one, it NEVER creates a chunk record from a client-asserted
    // hash/size — it only links an index to a chunk the server can itself prove
    // is already ACTIVE (locked FOR UPDATE, same as uploadChunk's dedup path).
    // If no such chunk exists yet, the client must fall back to uploadChunk()
    // and actually send the bytes.
    // ──────────────────────────────────────────────────────────────────────────

    public void linkExistingChunk(Long userId, Long sessionId, int chunkIndex, String clientHash, long expectedSize) {
        if (chunkIndex < 0) throw new IllegalArgumentException("chunkIndex must be >= 0");
        if (clientHash == null || !clientHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("chunkHash must be a 64-char lowercase SHA-256 hex string");
        }
        if (expectedSize <= 0) throw new IllegalArgumentException("Chunk size must be > 0");

        transactionTemplate.executeWithoutResult(status -> {
            UploadSession currentSession = sessionRepository.findByIdForUpdate(sessionId)
                    .orElseThrow(() -> new NotFoundException("Session not found"));
            if (!currentSession.getFile().getUser().getId().equals(userId)) {
                throw new com.filesync.common.exception.ForbiddenException("Unauthorized session access");
            }
            if (currentSession.getStatus() != UploadSessionStatus.INITIATED
                    && currentSession.getStatus() != UploadSessionStatus.UPLOADING) {
                throw new IllegalStateException("Session is no longer active: " + currentSession.getStatus());
            }

            // Proof the content is already durably stored — never trust the client's say-so.
            Chunk chunk = chunkRepository.findByHashForUpdate(clientHash)
                    .orElseThrow(() -> new NotFoundException(
                            "No existing chunk with hash " + clientHash + " — upload it with uploadChunk() instead"));
            if (chunk.getStatus() != ChunkStatus.ACTIVE) {
                throw new com.filesync.common.exception.ConflictException(
                        "Chunk " + clientHash + " is not ACTIVE (status=" + chunk.getStatus()
                                + ") — upload it with uploadChunk() instead");
            }
            if (chunk.getSize() != expectedSize) {
                throw new IllegalArgumentException(
                        "Chunk size mismatch: server has " + chunk.getSize() + " but client claims " + expectedSize);
            }

            Optional<UploadSessionChunk> existing = sessionChunkRepository
                    .findByUploadSessionIdAndChunkIndex(sessionId, chunkIndex);
            if (existing.isPresent()) {
                UploadSessionChunk existingChunk = existing.get();
                if (existingChunk.getChunk().getId().equals(chunk.getId())
                        && existingChunk.getChunkSize() == expectedSize) {
                    log.debug("UPLOAD_CHUNK_LINK_IDEMPOTENT: sessionId={}, chunkIndex={}", sessionId, chunkIndex);
                    return;
                }
                throw new com.filesync.common.exception.ConflictException(
                        "Chunk index " + chunkIndex + " already uploaded with different content");
            }

            UploadSessionChunk sessionChunk = new UploadSessionChunk(currentSession, chunkIndex, chunk, expectedSize);
            sessionChunk.setStatus("UPLOADED");
            sessionChunkRepository.save(sessionChunk);
            log.debug("UPLOAD_CHUNK_LINKED: sessionId={}, chunkIndex={}, hash={}", sessionId, chunkIndex, clientHash);
        });
    }

    // ──────────────────────────────────────────────────────────────────────────
    // completeUpload
    // ──────────────────────────────────────────────────────────────────────────

    public FileVersion completeUpload(Long userId, Long sessionId, Runnable leaseVerifier) {
        record ManifestInfo(Long chunkSize, String chunkingAlgorithm, Integer baseVersion, Long expectedFileSize,
                            String expectedFileHash, Long fileId, FileVersion existingVersion) {}

        ManifestInfo manifest = transactionTemplate.execute(status -> {
            UploadSession session = sessionRepository.findByIdForUpdate(sessionId)
                    .orElseThrow(() -> new NotFoundException("Session not found: " + sessionId));

            if (!session.getFile().getUser().getId().equals(userId)) {
                throw new com.filesync.common.exception.ForbiddenException("Unauthorized session access");
            }

            if (session.getStatus() == UploadSessionStatus.COMPLETED) {
                log.info("UPLOAD_COMPLETE_IDEMPOTENT: sessionId={}", sessionId);
                FileVersion existing = versionRepository.findByUploadSessionId(sessionId).orElse(null);
                return new ManifestInfo(null, null, null, null, null, null, existing);
            }

            if (session.getStatus() == UploadSessionStatus.ABORTED
                    || session.getStatus() == UploadSessionStatus.EXPIRED) {
                throw new IllegalStateException(
                    "Cannot complete session in terminal state: " + session.getStatus());
            }

            if (session.getStatus() != UploadSessionStatus.INITIATED
                    && session.getStatus() != UploadSessionStatus.UPLOADING) {
                throw new IllegalStateException(
                    "Session is in unexpected state: " + session.getStatus() + ". Another completion may be in progress.");
            }

            int updated = sessionRepository.transitionStatus(
                    sessionId, UploadSessionStatus.FINALIZING,
                    List.of(UploadSessionStatus.INITIATED, UploadSessionStatus.UPLOADING));
            if (updated == 0) {
                UploadSession reloaded = sessionRepository.findById(sessionId).orElseThrow();
                if (reloaded.getStatus() == UploadSessionStatus.COMPLETED) {
                    FileVersion existing = versionRepository.findByUploadSessionId(sessionId).orElse(null);
                    return new ManifestInfo(null, null, null, null, null, null, existing);
                }
                throw new IllegalStateException(
                    "Failed to transition session to FINALIZING; current state: " + reloaded.getStatus());
            }

            log.info("UPLOAD_SESSION_FINALIZING: sessionId={}", sessionId);
            return new ManifestInfo(
                    session.getChunkSize(),
                    session.getChunkingAlgorithm(),
                    session.getBaseVersion(),
                    session.getExpectedFileSize(),
                    session.getExpectedFileHash(),
                    session.getFile().getId(),
                    null);
        });

        if (manifest.existingVersion() != null) {
            return manifest.existingVersion();
        }

        // ── Phase B: Validate candidate chunks + verify full hash (outside TX) ─
        List<UploadSessionChunk> sessionChunks = sessionChunkRepository
                .findByUploadSessionIdOrderByChunkIndexAsc(sessionId);
        log.info("DEBUG: completeUpload found {} sessionChunks for sessionId={}", sessionChunks.size(), sessionId);
        UploadSession sessionForValidation = sessionRepository.findById(sessionId).orElseThrow();

        record ValidChunk(long chunkId, String chunkHash, int chunkIndex, long chunkSize) {}
        List<ValidChunk> validatedChunks;

        try {
            List<FileVersionChunk> validated = candidateValidator.validate(sessionForValidation, sessionChunks);
            validatedChunks = validated.stream()
                    .map(fvc -> new ValidChunk(
                            fvc.getChunk().getId(),
                            fvc.getChunk().getHash(),
                            fvc.getChunkIndex(),
                            fvc.getChunkSize()))
                    .toList();
        } catch (Exception e) {
            restoreFinalizingToUploading(sessionId, "Candidate validation failed: " + e.getMessage());
            throw new IllegalStateException("Candidate validation failed: " + e.getMessage(), e);
        }

        // Full SHA-256 verification from storage (outside transaction)
        if (manifest.expectedFileSize() == 0) {
            String emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
            if (!emptyHash.equalsIgnoreCase(manifest.expectedFileHash())) {
                restoreFinalizingToUploading(sessionId, "Empty file hash mismatch");
                throw new IllegalArgumentException("Full file hash mismatch");
            }
        } else if (!validatedChunks.isEmpty()) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long totalSize = 0;
                for (ValidChunk vc : validatedChunks) {
                    try (InputStream is = storageClient.readChunk(vc.chunkHash())) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = is.read(buffer)) != -1) {
                            digest.update(buffer, 0, read);
                            totalSize += read;
                        }
                    }
                }
                StringBuilder hexString = new StringBuilder();
                for (byte b : digest.digest()) {
                    hexString.append(String.format("%02x", b));
                }
                String calculatedHash = hexString.toString();

                if (!calculatedHash.equalsIgnoreCase(manifest.expectedFileHash())) {
                    restoreFinalizingToUploading(sessionId,
                        "Full file hash mismatch. Expected: " + manifest.expectedFileHash()
                                + " Calculated: " + calculatedHash);
                    throw new IllegalArgumentException("Full file hash mismatch");
                }
                if (totalSize != manifest.expectedFileSize()) {
                    restoreFinalizingToUploading(sessionId,
                        "Total size mismatch. Expected: " + manifest.expectedFileSize() + " Got: " + totalSize);
                    throw new IllegalArgumentException("Total file size mismatch");
                }
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                restoreFinalizingToUploading(sessionId, "Hash verification error: " + e.getMessage());
                throw new RuntimeException("Failed to verify file hash: " + e.getMessage(), e);
            }
        }

        // ── Phase C: Atomic OCC commit ──────────────────────────────────────
        final List<ValidChunk> chunksToCommit = validatedChunks;
        final String chunkingAlgorithm = manifest.chunkingAlgorithm();
        try {
            return transactionTemplate.execute(status -> {
                if (leaseVerifier != null) leaseVerifier.run();
                UploadSession currentSession = sessionRepository.findByIdForUpdate(sessionId).orElseThrow();

                if (currentSession.getStatus() == UploadSessionStatus.COMPLETED) {
                    log.info("UPLOAD_COMPLETE_IDEMPOTENT_PHASE_C: sessionId={}", sessionId);
                    return versionRepository.findByUploadSessionId(sessionId).orElse(null);
                }

                if (currentSession.getStatus() != UploadSessionStatus.FINALIZING) {
                    throw new IllegalStateException(
                        "Session is no longer in FINALIZING state: " + currentSession.getStatus());
                }

                FileEntry file = fileRepository.findByIdAndUserIdForUpdate(
                                currentSession.getFile().getId(), userId)
                        .orElseThrow(() -> new NotFoundException("File not found"));

                if (currentSession.getBaseVersion() != null
                        && !currentSession.getBaseVersion().equals(file.getCurrentVersion())) {
                    log.warn("UPLOAD_OCC_CONFLICT: sessionId={}, expectedBase={}, serverVersion={}",
                            sessionId, currentSession.getBaseVersion(), file.getCurrentVersion());
                    throw new SyncConflictException(
                            file.getId(), currentSession.getBaseVersion(), file.getCurrentVersion());
                }

                // Create FileVersion with chunking algorithm recorded
                FileVersion newVersion = new FileVersion(
                        file, currentSession.getTargetVersion(),
                        currentSession.getExpectedFileHash(), currentSession.getExpectedFileSize(),
                        currentSession.getDevice(), "UPLOAD", sessionId,
                        chunkingAlgorithm != null ? chunkingAlgorithm : "CDC");
                versionRepository.save(newVersion);

                for (var vc : chunksToCommit) {
                    Chunk chunkRef = chunkRepository.getReferenceById(vc.chunkId());
                    FileVersionChunk fvc = new FileVersionChunk(newVersion, vc.chunkIndex(), chunkRef, vc.chunkSize());
                    newVersion.getChunks().add(fvc);
                }

                file.setCurrentVersion(currentSession.getTargetVersion());
                file.setCurrentVersionId(newVersion);
                file.setFileHash(currentSession.getExpectedFileHash());
                file.setFileSize(currentSession.getExpectedFileSize());
                fileRepository.save(file);

                int completed = sessionRepository.transitionStatus(
                        sessionId, UploadSessionStatus.COMPLETED,
                        List.of(UploadSessionStatus.FINALIZING));
                if (completed == 0) {
                    throw new IllegalStateException("Failed to transition session to COMPLETED");
                }

                log.info("UPLOAD_SESSION_COMPLETED: sessionId={}, fileId={}, version={}, algorithm={}",
                        sessionId, file.getId(), currentSession.getTargetVersion(), chunkingAlgorithm);

                SyncEvent event = SyncEvent.fileUpdated(userId, file.getId(), file.getName(), file.getCurrentVersion());
                try {
                    redisTemplate.convertAndSend("sync-events", objectMapper.writeValueAsString(event));
                } catch (Exception e) {
                    log.error("Failed to publish Redis event", e);
                }
                return newVersion;
            });
        } catch (SyncConflictException occ) {
            restoreFinalizingToUploading(sessionId,
                "OCC conflict: client base=" + occ.getClientBaseVersion()
                        + " server=" + occ.getServerCurrentVersion());
            throw occ;
        }
    }

    private void restoreFinalizingToUploading(Long sessionId, String reason) {
        try {
            transactionTemplate.executeWithoutResult(s -> {
                int updated = sessionRepository.transitionStatus(
                        sessionId, UploadSessionStatus.UPLOADING,
                        List.of(UploadSessionStatus.FINALIZING));
                if (updated > 0) {
                    log.warn("UPLOAD_SESSION_FINALIZATION_FAILED: sessionId={}, restored to UPLOADING, reason={}",
                            sessionId, reason);
                }
            });
        } catch (Exception ex) {
            log.error("UPLOAD_SESSION_RESTORE_FAILED: sessionId={}", sessionId, ex);
        }
    }

    @Scheduled(fixedRateString = "${app.sync.finalizing-recovery-rate:3600000}")
    @Transactional
    public void recoverStuckFinalizingSessions() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(1);
        List<UploadSession> stuck = sessionRepository.findStuckFinalizingSessions(cutoff);
        for (UploadSession s : stuck) {
            int updated = sessionRepository.transitionStatus(
                    s.getId(), UploadSessionStatus.UPLOADING,
                    List.of(UploadSessionStatus.FINALIZING));
            if (updated > 0) {
                log.warn("UPLOAD_SESSION_STUCK_FINALIZING_RECOVERED: sessionId={}", s.getId());
            }
        }
    }

    @Scheduled(fixedRateString = "${app.sync.session-expiry-rate:300000}")
    @Transactional
    public void expireOldSessions() {
        int expiredCount = sessionRepository.expireOldSessions();
        if (expiredCount > 0) {
            log.info("UPLOAD_SESSION_EXPIRED_BATCH: count={}", expiredCount);
        }
    }

    @Scheduled(fixedRateString = "${app.sync.session-cleanup-rate:86400000}")
    @Transactional
    public void cleanupTerminalSessions() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        int deletedCount = sessionRepository.deleteTerminalSessionsOlderThan(cutoff);
        if (deletedCount > 0) {
            log.info("UPLOAD_SESSION_CLEANUP: deletedCount={}, olderThan={}", deletedCount, cutoff);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Delta
    //
    // CDC-aware delta computation:
    //   Old (FIXED): compares by chunkIndex — serverChunkMap.get(entry.chunkIndex())
    //   New (CDC):   compares by hash identity — missingHashes = clientHashes - serverHashes
    //
    // The response changes from List<Integer> (missing indices) to List<String> (missing hashes).
    // This is a semantic breaking change in the API but is required for correctness: with CDC,
    // the same content at a different position has the same hash and does NOT need re-uploading.
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Compute the set of chunk hashes that the client needs to upload.
     *
     * <p>For CDC sessions: delta is computed as a set difference on SHA-256 hashes.
     * A chunk that exists on the server (regardless of its old position) does NOT need
     * to be uploaded again — only truly new content is requested.</p>
     *
     * @param sessionId if non-null, records totalChunks on the session for getMissingChunks()
     * @return ordered list of SHA-256 hashes that the client must upload
     */
    @Transactional
    public List<String> computeDelta(Long userId, Long fileId, Long sessionId, Integer baseVersion,
                                     Long expectedFileSize, List<DeltaRequest.ChunkManifestEntry> clientManifest) {
        FileEntry file = fileRepository.findByIdAndUserId(fileId, userId)
                .orElseThrow(() -> new NotFoundException("File not found"));

        if (file.getCurrentVersion() > 0 && baseVersion == null) {
            throw new IllegalArgumentException("baseVersion is required for existing files");
        }
        if (baseVersion != null && !baseVersion.equals(file.getCurrentVersion())) {
            throw new SyncConflictException(fileId, baseVersion, file.getCurrentVersion());
        }

        // Validate chunk indices are contiguous from 0 and hashes are valid
        for (int i = 0; i < clientManifest.size(); i++) {
            DeltaRequest.ChunkManifestEntry entry = clientManifest.get(i);
            if (entry.chunkIndex() != i) {
                throw new IllegalArgumentException("Manifest chunks must be contiguous and ordered from 0");
            }
            if (entry.chunkHash() == null || !entry.chunkHash().matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid chunk hash at index " + i + ": " + entry.chunkHash());
            }
        }

        // Record totalChunks on the session if provided (enables getMissingChunks for CDC)
        if (sessionId != null && !clientManifest.isEmpty()) {
            sessionRepository.updateTotalChunks(sessionId, clientManifest.size());
        }

        // All chunks needed if no previous version
        if (file.getCurrentVersionId() == null) {
            return clientManifest.stream()
                    .map(DeltaRequest.ChunkManifestEntry::chunkHash)
                    .distinct()
                    .toList();
        }

        // Build the server's hash set from the current version
        List<FileVersionChunk> serverChunks = file.getCurrentVersionId().getChunks();
        Set<String> serverHashes = serverChunks.stream()
                .map(fvc -> fvc.getChunk().getHash().toLowerCase())
                .collect(Collectors.toCollection(HashSet::new));

        // Delta = client hashes NOT present on server (hash-set identity, not index identity)
        // This is the CDC-correct approach: same content at a different position is reused.
        List<String> missingHashes = new ArrayList<>();
        Set<String> alreadyAdded = new HashSet<>();
        for (DeltaRequest.ChunkManifestEntry entry : clientManifest) {
            String hash = entry.chunkHash().toLowerCase();
            if (!serverHashes.contains(hash) && alreadyAdded.add(hash)) {
                missingHashes.add(hash);
            }
        }

        log.info("DELTA_COMPUTED: fileId={}, userId={}, clientChunks={}, serverChunks={}, missingChunks={}",
                fileId, userId, clientManifest.size(), serverChunks.size(), missingHashes.size());

        return missingHashes;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Session queries
    // ──────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public UploadSessionResponse getSession(Long userId, Long sessionId) {
        UploadSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("Session not found"));
        if (!session.getFile().getUser().getId().equals(userId)) {
            throw new com.filesync.common.exception.ForbiddenException("Unauthorized session access");
        }
        return UploadSessionResponse.from(session);
    }

    @Transactional(readOnly = true)
    public List<Integer> getMissingChunks(Long userId, Long sessionId) {
        UploadSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("Session not found"));
        if (!session.getFile().getUser().getId().equals(userId)) {
            throw new com.filesync.common.exception.ForbiddenException("Unauthorized session access");
        }

        // Zero-byte file: no chunks expected
        if (session.getExpectedFileSize() == 0) {
            return List.of();
        }

        List<UploadSessionChunk> uploadedChunks =
                sessionChunkRepository.findByUploadSessionIdOrderByChunkIndexAsc(sessionId);
        List<Integer> uploadedIndices = uploadedChunks.stream()
                .map(UploadSessionChunk::getChunkIndex).toList();

        int expectedChunks;
        if (session.getTotalChunks() != null) {
            // CDC: use totalChunks recorded when delta was computed
            expectedChunks = session.getTotalChunks();
        } else {
            // CDC without delta yet: totalChunks unknown — return empty (client must compute delta first)
            log.warn("getMissingChunks called for CDC session {} before delta was computed; returning empty", sessionId);
            return List.of();
        }

        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < expectedChunks; i++) {
            if (!uploadedIndices.contains(i)) {
                missing.add(i);
            }
        }
        return missing;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Abort
    // ──────────────────────────────────────────────────────────────────────────

    @Transactional
    public void abortSession(Long userId, Long sessionId) {
        UploadSession session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new NotFoundException("Session not found"));
        if (!session.getFile().getUser().getId().equals(userId)) {
            throw new com.filesync.common.exception.ForbiddenException("Unauthorized session access");
        }

        if (session.getStatus() == UploadSessionStatus.ABORTED) {
            return;
        }
        if (session.getStatus() == UploadSessionStatus.COMPLETED
                || session.getStatus() == UploadSessionStatus.EXPIRED) {
            throw new IllegalStateException("Cannot abort session in terminal state: " + session.getStatus());
        }

        int updated = sessionRepository.transitionStatus(
                sessionId, UploadSessionStatus.ABORTED,
                List.of(UploadSessionStatus.INITIATED, UploadSessionStatus.UPLOADING, UploadSessionStatus.FINALIZING));

        if (updated == 0) {
            UploadSession reloaded = sessionRepository.findById(sessionId).orElseThrow();
            if (reloaded.getStatus() == UploadSessionStatus.ABORTED) {
                return;
            }
            throw new IllegalStateException("Cannot abort session in state: " + reloaded.getStatus());
        }
        log.info("UPLOAD_SESSION_ABORTED: sessionId={}, userId={}", sessionId, userId);
    }
}
