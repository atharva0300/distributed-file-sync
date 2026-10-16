package com.filesync.sync.dto;

import jakarta.validation.constraints.NotNull;

public record QueueSyncOperationRequest(
        @NotNull Long deviceId,
        Long fileId,
        @NotNull String operation,
        Integer baseVersion,
        String payload,
        @NotNull String clientOperationId,
        Long uploadSessionId
) {}
