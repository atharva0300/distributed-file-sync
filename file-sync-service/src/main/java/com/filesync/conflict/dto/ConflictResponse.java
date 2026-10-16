package com.filesync.conflict.dto;

import com.filesync.conflict.entity.Conflict;

import java.time.LocalDateTime;

public record ConflictResponse(
        Long conflictId,
        Long operationId,
        Long fileId,
        String fileName,
        int serverVersion,
        int clientBaseVersion,
        String status,
        LocalDateTime createdAt
) {
    public static ConflictResponse from(Conflict conflict) {
        return new ConflictResponse(
                conflict.getId(),
                conflict.getOperation().getId(),
                conflict.getFile().getId(),
                conflict.getFile().getName(),
                conflict.getServerVersion(),
                conflict.getClientBaseVersion(),
                conflict.getStatus(),
                conflict.getCreatedAt()
        );
    }
}
