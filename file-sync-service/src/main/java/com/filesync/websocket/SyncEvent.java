package com.filesync.websocket;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

public record SyncEvent(
        String eventType,
        Long userId,
        Long fileId,
        String fileName,
        int version,
        String message,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime timestamp
) {
    public static SyncEvent fileUpdated(Long userId, Long fileId, String fileName, int version) {
        return new SyncEvent("FILE_UPDATED", userId, fileId, fileName, version,
                "File updated to version " + version, LocalDateTime.now());
    }

    public static SyncEvent fileDeleted(Long userId, Long fileId, String fileName) {
        return new SyncEvent("FILE_DELETED", userId, fileId, fileName, 0,
                "File deleted", LocalDateTime.now());
    }

    public static SyncEvent syncStarted(Long userId, Long fileId, String fileName) {
        return new SyncEvent("SYNC_STARTED", userId, fileId, fileName, 0,
                "Sync started", LocalDateTime.now());
    }

    public static SyncEvent syncCompleted(Long userId, Long fileId, String fileName, int version) {
        return new SyncEvent("SYNC_COMPLETED", userId, fileId, fileName, version,
                "Sync completed, version " + version, LocalDateTime.now());
    }

    public static SyncEvent conflictDetected(Long userId, Long fileId, String fileName) {
        return new SyncEvent("CONFLICT_DETECTED", userId, fileId, fileName, 0,
                "Sync conflict detected — resolution required", LocalDateTime.now());
    }
}
