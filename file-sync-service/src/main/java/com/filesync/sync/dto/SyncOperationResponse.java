package com.filesync.sync.dto;

import com.filesync.sync.entity.SyncOperation;

import java.time.LocalDateTime;

public record SyncOperationResponse(
        Long id,
        Long deviceId,
        Long fileId,
        String operation,
        String status,
        Integer baseVersion,
        int retryCount,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static SyncOperationResponse from(SyncOperation op) {
        return new SyncOperationResponse(
                op.getId(), 
                op.getDevice().getId(), 
                op.getFile() != null ? op.getFile().getId() : null,
                op.getOperationType(), 
                op.getStatus(), 
                op.getBaseVersion(),
                op.getRetryCount(), 
                op.getLastError(), 
                op.getCreatedAt(), 
                op.getUpdatedAt()
        );
    }
}
