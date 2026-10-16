package com.filesync.file.dto;

import jakarta.validation.constraints.NotNull;

public record MoveRequest(
        @NotNull Long fileId,
        Long targetFolderId  // null = move to root
) {}
