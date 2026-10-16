package com.filesync.version.service;

import com.filesync.common.exception.NotFoundException;
import com.filesync.device.entity.Device;
import com.filesync.device.repository.DeviceRepository;
import com.filesync.file.entity.FileEntry;
import com.filesync.file.repository.FileRepository;
import com.filesync.version.entity.FileVersion;
import com.filesync.version.entity.FileVersionChunk;
import com.filesync.version.repository.FileVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class VersionService {

    private final FileVersionRepository versionRepository;
    private final FileRepository fileRepository;
    private final DeviceRepository deviceRepository;

    /**
     * Create a new version entity in PostgreSQL.
     * Called after every successful file change or upload completion.
     */
    @Transactional
    public FileVersion createVersion(FileEntry file, Device device, String changeType) {
        FileVersion version = new FileVersion(
                file,
                file.getCurrentVersion(),
                file.getFileHash(),
                file.getFileSize(),
                device,
                changeType
        );

        FileVersion saved = versionRepository.save(version);
        log.info("VERSION_CREATED: fileId={}, version={}, changeType={}", file.getId(), file.getCurrentVersion(), changeType);
        return saved;
    }

    /** List all versions for a file, newest first. */
    @Transactional(readOnly = true)
    public List<FileVersion> listVersions(Long fileId) {
        return versionRepository.findByFileIdOrderByVersionNumberDesc(fileId);
    }

    /** Find a specific version for a file. */
    @Transactional(readOnly = true)
    public Optional<FileVersion> findVersion(Long fileId, int versionNumber) {
        return versionRepository.findByFileIdAndVersionNumber(fileId, versionNumber);
    }

    /**
     * Restore a previous version.
     *
     * Strategy: restoring v2 creates a new next version (e.g. v6, not overwriting history).
     * This preserves auditability and allows reverting restores.
     */
    @Transactional
    public FileVersion restoreVersion(Long fileId, int versionNumber, Long userId, Long deviceId, String deviceName) {
        return restoreVersion(fileId, versionNumber, userId, deviceId);
    }

    @Transactional
    public FileVersion restoreVersion(Long fileId, int versionNumber, Long userId, Long deviceId) {
        FileEntry file = fileRepository.findByIdAndUserIdForUpdate(fileId, userId)
                .orElseThrow(() -> new NotFoundException("File not found: " + fileId));

        FileVersion targetVersion = versionRepository.findByFileIdAndVersionNumber(fileId, versionNumber)
                .orElseThrow(() -> new NotFoundException("Version " + versionNumber + " not found for file " + fileId));

        Device device = deviceId != null ? deviceRepository.findById(deviceId).orElse(null) : null;
        int nextVersionNumber = file.getCurrentVersion() + 1;

        FileVersion newVersion = new FileVersion(file, nextVersionNumber, targetVersion.getFileHash(), targetVersion.getFileSize(), device, "RESTORE_FROM_V" + versionNumber);
        newVersion = versionRepository.save(newVersion);

        // Copy chunks from targetVersion
        for (FileVersionChunk oldChunk : targetVersion.getChunks()) {
            FileVersionChunk fvc = new FileVersionChunk(newVersion, oldChunk.getChunkIndex(), oldChunk.getChunk(), oldChunk.getChunkSize());
            newVersion.getChunks().add(fvc);
        }
        newVersion = versionRepository.save(newVersion);

        // Update file metadata
        file.setCurrentVersion(nextVersionNumber);
        file.setCurrentVersionId(newVersion);
        file.setFileHash(targetVersion.getFileHash());
        file.setFileSize(targetVersion.getFileSize());
        fileRepository.save(file);

        log.info("VERSION_RESTORED: fileId={}, restoredToVersion={}, newVersion={}", fileId, versionNumber, nextVersionNumber);
        return newVersion;
    }

    @org.springframework.beans.factory.annotation.Value("${app.sync.max-versions-per-file:20}")
    private int maxVersionsPerFile;

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "3600000") // Every hour
    @Transactional
    public void cleanupOldVersions() {
        log.info("Starting version cleanup, keeping up to {} versions per file", maxVersionsPerFile);
        int deletedCount = versionRepository.deleteOldVersions(maxVersionsPerFile);
        if (deletedCount > 0) {
            log.info("Finished version cleanup. Deleted {} old versions.", deletedCount);
        }
    }
}
