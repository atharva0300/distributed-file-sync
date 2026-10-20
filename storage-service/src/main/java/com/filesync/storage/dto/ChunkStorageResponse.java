package com.filesync.storage.dto;

public record ChunkStorageResponse(
    String hash,
    String storageKey,
    boolean alreadyExisted,
    long size
) {}

