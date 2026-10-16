package com.filesync.file.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateFolderRequest(
        @NotBlank String name,
        Long parentFolderId
) {}
