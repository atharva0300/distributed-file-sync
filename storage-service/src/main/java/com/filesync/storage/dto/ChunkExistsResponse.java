package com.filesync.storage.dto;

public record ChunkExistsResponse(
    String hash,
    boolean exists
) {}

