package com.filesync.file.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RenameRequest(
        @NotNull Long fileId,
        @NotBlank String newName
) {}
