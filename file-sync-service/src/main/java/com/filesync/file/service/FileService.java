package com.filesync.file.service;

import com.filesync.auth.entity.User;
import com.filesync.auth.repository.UserRepository;
import com.filesync.common.exception.ConflictException;
import com.filesync.common.exception.ForbiddenException;
import com.filesync.common.exception.NotFoundException;
import com.filesync.common.exception.SyncConflictException;
import com.filesync.file.dto.*;
import com.filesync.file.entity.FileEntry;
import com.filesync.file.entity.SyncStatus;
import com.filesync.file.repository.FileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private final FileRepository fileRepository;
    private final UserRepository userRepository;
    private final com.filesync.upload.repository.UploadSessionRepository sessionRepository;

    // ──────────────────────────────────────────────────────────────────────────
    // Folder creation
    // ──────────────────────────────────────────────────────────────────────────

    @Transactional
    public FileResponse createFolder(Long userId, CreateFolderRequest request) {
        validateName(request.name());
        User user = getUser(userId);
        FileEntry parent = resolveParent(userId, request.parentFolderId());

        FileEntry folder = new FileEntry(user, parent, request.name(), true);
        try {
            fileRepository.saveAndFlush(folder);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("A file or folder named '" + request.name() + "' already exists here.");
        }

        log.info("FOLDER_CREATED: userId={}, folderId={}, name={}", userId, folder.getId(), request.name());
        return FileResponse.from(folder);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // File initialization (for new file upload)
    // ──────────────────────────────────────────────────────────────────────────

    @Transactional
    public FileResponse initUpload(Long userId, InitUploadRequest request) {
        validateName(request.fileName());
        User user = getUser(userId);
        FileEntry parent = resolveParent(userId, request.parentFolderId());

        // If an incomplete file exists with same name and no active session → reuse its slot
        java.util.Optional<FileEntry> existing = parent == null
                ? fileRepository.findByUserIdAndParentFolderIsNullAndNameAndDeletedFalse(userId, request.fileName())
                : fileRepository.findByUserIdAndParentFolderIdAndNameAndDeletedFalse(userId, parent.getId(), request.fileName());

        if (existing.isPresent() && !existing.get().isUploadComplete()) {
            boolean hasActiveSession = sessionRepository.existsByFileIdAndStatusIn(existing.get().getId(),
                    List.of(com.filesync.upload.entity.UploadSessionStatus.INITIATED,
                            com.filesync.upload.entity.UploadSessionStatus.UPLOADING,
                            com.filesync.upload.entity.UploadSessionStatus.FINALIZING));
            if (hasActiveSession) {
                throw new ConflictException("An active upload is already in progress for this file.");
            }
            // Safe to reuse — no active session
            fileRepository.delete(existing.get());
            fileRepository.flush();
        } else if (existing.isPresent() && existing.get().isUploadComplete()) {
            // Existing complete file — return it for the caller to use as an update upload
            log.info("FILE_EXISTS_RETURNING_FOR_UPDATE: userId={}, fileId={}", userId, existing.get().getId());
            return FileResponse.from(existing.get());
        }

        FileEntry file = new FileEntry(user, parent, request.fileName(), false);
        if (request.fileSize() != null) {
            file.setFileSize(request.fileSize());
        }
        try {
            fileRepository.saveAndFlush(file);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("A file or folder named '" + request.fileName() + "' already exists here.");
        }

        log.info("FILE_UPLOAD_INIT: userId={}, fileId={}, name={}", userId, file.getId(), request.fileName());
        return FileResponse.from(file);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // List / Get
    // ──────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<FileResponse> listFiles(Long userId, Long parentFolderId) {
        List<FileEntry> files = parentFolderId == null
                ? fileRepository.findByUserIdAndParentFolderIsNullAndDeletedFalse(userId)
                : fileRepository.findByUserIdAndParentFolderIdAndDeletedFalse(userId, parentFolderId);
        return files.stream().map(FileResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public FileResponse getFile(Long userId, Long fileId) {
        return FileResponse.from(getFileAndVerifyOwnership(userId, fileId));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Delete
    // ──────────────────────────────────────────────────────────────────────────

    @Transactional
    public void deleteFile(Long userId, Long fileId) {
        FileEntry file = getFileAndVerifyOwnership(userId, fileId);
        markDeletedRecursive(file);
        log.info("FILE_DELETED: userId={}, fileId={}", userId, fileId);
    }

    /**
     * Atomic delete with OCC baseVersion check.
     * Used by SyncWorker to prevent TOCTOU races.
     *
     * Concurrency invariant:
     *   Lock FileEntry FOR UPDATE → verify baseVersion → soft-delete
     *   All in one transaction. No gap between check and mutation.
     */
    @Transactional
    public void deleteFileAtomically(Long userId, Long fileId, Integer expectedBaseVersion, Runnable leaseVerifier) {
        if (leaseVerifier != null) leaseVerifier.run();
        FileEntry file = fileRepository.findByIdAndUserIdForUpdate(fileId, userId)
                .orElseThrow(() -> new NotFoundException("File not found: " + fileId));

        if (expectedBaseVersion != null && file.getCurrentVersion() != expectedBaseVersion) {
            throw new SyncConflictException(fileId, expectedBaseVersion, file.getCurrentVersion());
        }

        markDeletedRecursive(file);
        log.info("FILE_DELETED_ATOMIC: userId={}, fileId={}, baseVersion={}", userId, fileId, expectedBaseVersion);
    }

    private void markDeletedRecursive(FileEntry file) {
        file.setDeleted(true);
        fileRepository.save(file);
        if (file.isFolder()) {
            List<FileEntry> children = fileRepository
                    .findByUserIdAndParentFolderIdAndDeletedFalse(file.getUser().getId(), file.getId());
            for (FileEntry child : children) {
                markDeletedRecursive(child);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Rename
    // ──────────────────────────────────────────────────────────────────────────

    @Transactional
    public FileResponse renameFile(Long userId, RenameRequest request) {
        validateName(request.newName());
        FileEntry file = getFileAndVerifyOwnership(userId, request.fileId());
        Long parentId = file.getParentFolder() != null ? file.getParentFolder().getId() : null;

        file.setName(request.newName());
        try {
            fileRepository.saveAndFlush(file);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("A file or folder named '" + request.newName() + "' already exists here.");
        }

        log.info("FILE_RENAMED: userId={}, fileId={}, newName={}", userId, file.getId(), request.newName());
        return FileResponse.from(file);
    }

    /**
     * Atomic rename with OCC baseVersion check.
     * Used by SyncWorker.
     *
     * Concurrency invariant:
     *   Lock FileEntry FOR UPDATE → verify baseVersion → rename
     *   All in one transaction.
     */
    @Transactional
    public FileResponse renameFileAtomically(Long userId, Long fileId, Integer expectedBaseVersion, String newName, Runnable leaseVerifier) {
        if (leaseVerifier != null) leaseVerifier.run();
        validateName(newName);
        FileEntry file = fileRepository.findByIdAndUserIdForUpdate(fileId, userId)
                .orElseThrow(() -> new NotFoundException("File not found: " + fileId));

        if (expectedBaseVersion != null && file.getCurrentVersion() != expectedBaseVersion) {
            throw new SyncConflictException(fileId, expectedBaseVersion, file.getCurrentVersion());
        }

        file.setName(newName);
        try {
            fileRepository.saveAndFlush(file);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("A file or folder named '" + newName + "' already exists here.");
        }

        log.info("FILE_RENAMED_ATOMIC: userId={}, fileId={}, newName={}, baseVersion={}", userId, fileId, newName, expectedBaseVersion);
        return FileResponse.from(file);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Move
    // ──────────────────────────────────────────────────────────────────────────

    @Transactional
    public FileResponse moveFile(Long userId, MoveRequest request) {
        FileEntry file = getFileAndVerifyOwnership(userId, request.fileId());
        FileEntry target = resolveParent(userId, request.targetFolderId());
        validateNoCycle(file, target);

        file.setParentFolder(target);
        try {
            fileRepository.saveAndFlush(file);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("A file or folder named '" + file.getName() + "' already exists in the target folder.");
        }

        log.info("FILE_MOVED: userId={}, fileId={}, targetFolderId={}", userId, file.getId(), request.targetFolderId());
        return FileResponse.from(file);
    }

    /**
     * Atomic move with OCC baseVersion check.
     * Used by SyncWorker.
     *
     * Concurrency invariant:
     *   Lock FileEntry FOR UPDATE → verify baseVersion → move
     *   All in one transaction.
     */
    @Transactional
    public FileResponse moveFileAtomically(Long userId, Long fileId, Integer expectedBaseVersion, Long targetFolderId, Runnable leaseVerifier) {
        if (leaseVerifier != null) leaseVerifier.run();
        FileEntry file = fileRepository.findByIdAndUserIdForUpdate(fileId, userId)
                .orElseThrow(() -> new NotFoundException("File not found: " + fileId));

        if (expectedBaseVersion != null && file.getCurrentVersion() != expectedBaseVersion) {
            throw new SyncConflictException(fileId, expectedBaseVersion, file.getCurrentVersion());
        }

        FileEntry target = resolveParent(userId, targetFolderId);
        validateNoCycle(file, target);

        file.setParentFolder(target);
        try {
            fileRepository.saveAndFlush(file);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("A file or folder named '" + file.getName() + "' already exists in the target folder.");
        }

        log.info("FILE_MOVED_ATOMIC: userId={}, fileId={}, targetFolderId={}, baseVersion={}", userId, fileId, targetFolderId, expectedBaseVersion);
        return FileResponse.from(file);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Download manifest
    // ──────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DownloadManifest getDownloadManifest(Long userId, Long fileId) {
        FileEntry file = getFileAndVerifyOwnership(userId, fileId);

        if (file.getCurrentVersionId() == null) {
            // Zero-byte file or no committed version yet
            return new DownloadManifest(file.getName(), file.getFileSize() != null ? file.getFileSize() : 0L, List.of(), List.of());
        }

        var chunks = file.getCurrentVersionId().getChunks();
        if (chunks.isEmpty()) {
            // Zero-byte file with a committed version
            return new DownloadManifest(file.getName(), file.getFileSize() != null ? file.getFileSize() : 0L, List.of(), List.of());
        }

        // Sort by chunkIndex to ensure proper order
        var sortedChunks = chunks.stream()
                .sorted(java.util.Comparator.comparingInt(c -> c.getChunkIndex()))
                .toList();

        List<String> chunkHashes = sortedChunks.stream()
                .map(chunk -> chunk.getChunk().getHash())
                .toList();

        List<DownloadManifest.ChunkInfo> chunkInfos = sortedChunks.stream()
                .map(chunk -> new DownloadManifest.ChunkInfo(chunk.getChunk().getHash(), chunk.getChunkSize()))
                .toList();

        return new DownloadManifest(
                encodeFilenameForContentDisposition(file.getName()),
                file.getFileSize(),
                chunkHashes,
                chunkInfos);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Package-accessible helpers
    // ──────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public FileEntry getFileAndVerifyOwnership(Long userId, Long fileId) {
        return fileRepository.findByIdAndUserIdAndDeletedFalse(fileId, userId)
                .orElseThrow(() -> new NotFoundException("File not found: " + fileId));
    }

    @Transactional
    public void save(FileEntry file) {
        fileRepository.save(file);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
    }

    private FileEntry resolveParent(Long userId, Long parentFolderId) {
        if (parentFolderId == null) return null;
        FileEntry parent = fileRepository.findByIdAndUserIdAndDeletedFalse(parentFolderId, userId)
                .orElseThrow(() -> new NotFoundException("Parent folder not found: " + parentFolderId));
        if (!parent.isFolder()) {
            throw new IllegalArgumentException("Target ID " + parentFolderId + " is not a folder");
        }
        return parent;
    }

    private void validateNoCycle(FileEntry file, FileEntry target) {
        FileEntry current = target;
        while (current != null) {
            if (current.getId().equals(file.getId())) {
                throw new IllegalArgumentException("Cannot move a folder into itself or its descendant");
            }
            current = current.getParentFolder();
        }
    }

    /**
     * Validates a filename/folder name for security and well-formedness.
     * Enforced at all entry points: createFolder, initUpload, rename.
     */
    public static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name must not be blank");
        }
        if (name.length() > 255) {
            throw new IllegalArgumentException("Name must not exceed 255 characters");
        }
        if (name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("Name must not be '.' or '..'");
        }
        if (name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("Name must not contain path separators");
        }
        // Reject control characters
        for (char c : name.toCharArray()) {
            if (c < 32 || c == 127) {
                throw new IllegalArgumentException("Name contains invalid control character: " + (int) c);
            }
        }
    }

    /**
     * Safely encode filename for Content-Disposition header.
     * Prevents header injection via filename.
     */
    private static String encodeFilenameForContentDisposition(String name) {
        // Remove characters that are unsafe in Content-Disposition filename param
        return name.replaceAll("[\"\\\\]", "_");
    }
}
