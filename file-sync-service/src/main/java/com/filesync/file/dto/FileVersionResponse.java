package com.filesync.file.dto;

import com.filesync.version.entity.FileVersion;

import java.time.LocalDateTime;

public record FileVersionResponse(
        Long id,
        int versionNumber,
        String fileHash,
        Long fileSize,
        Long createdByDeviceId,
        String changeType,
        LocalDateTime createdAt
) {
    public static FileVersionResponse from(FileVersion version) {
        return new FileVersionResponse(
                version.getId(),
                version.getVersionNumber(),
                version.getFileHash(),
                version.getFileSize(),
                version.getCreatedByDevice() != null ? version.getCreatedByDevice().getId() : null,
                version.getChangeType(),
                version.getCreatedAt()
        );
    }
}
