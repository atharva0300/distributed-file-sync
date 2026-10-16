package com.filesync.conflict.dto;

import jakarta.validation.constraints.NotNull;

public record ConflictResolutionRequest(
        @NotNull ResolutionStrategy strategy
) {
    public enum ResolutionStrategy {
        KEEP_SERVER,        // discard client changes, keep current server version
        KEEP_LOCAL,         // create new version from the client's conflicting data
        CREATE_CONFLICT_COPY // create a separate file "filename (conflict-copy-deviceX).ext"
    }
}
