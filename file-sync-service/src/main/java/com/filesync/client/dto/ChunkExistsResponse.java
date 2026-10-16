package com.filesync.client.dto;

public record ChunkExistsResponse(
    String hash,
    boolean exists
) {}
