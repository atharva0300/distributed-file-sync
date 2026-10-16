package com.filesync.file.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record InitUploadRequest(
        @NotBlank String fileName,
        @NotNull @Min(0) Long fileSize,
        Long parentFolderId,
        int totalChunks
) {}
