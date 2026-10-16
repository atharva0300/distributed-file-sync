package com.filesync.websocket.event;

public record FileUpdatedEvent(
        Long userId,
        Long fileId,
        String fileName,
        int version
) {}
