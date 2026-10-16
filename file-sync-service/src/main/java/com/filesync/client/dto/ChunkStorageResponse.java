package com.filesync.client.dto;

public record ChunkStorageResponse(
    String hash,
    String storageKey,
    boolean alreadyExisted,
    long size
) {}
