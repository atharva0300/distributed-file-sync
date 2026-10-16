package com.filesync.file.dto;

import com.filesync.file.entity.FileEntry;
import com.filesync.file.entity.SyncStatus;

import java.time.LocalDateTime;

public record FileResponse(
        Long id,
        Long parentFolderId,
        String name,
        String path,
        int currentVersion,
        String fileHash,
        Long fileSize,
        boolean isFolder,
        SyncStatus syncStatus,
        boolean uploadComplete,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static FileResponse from(FileEntry f) {
        return new FileResponse(
                f.getId(),
                f.getParentFolder() != null ? f.getParentFolder().getId() : null,
                f.getName(),
                f.getPath(),
                f.getCurrentVersion(),
                f.getFileHash(),
                f.getFileSize(),
                f.isFolder(),
                f.getSyncStatus(),
                f.isUploadComplete(),
                f.getCreatedAt(),
                f.getUpdatedAt()
        );
    }
}
