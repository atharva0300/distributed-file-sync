package com.filesync.notification;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * A "something changed, re-fetch via REST" hint pushed to a user's connected clients.
 * Carries metadata only, never file content.
 */
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
}
