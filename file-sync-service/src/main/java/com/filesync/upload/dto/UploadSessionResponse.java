package com.filesync.upload.dto;

import com.filesync.upload.entity.UploadSession;

import java.time.LocalDateTime;

public record UploadSessionResponse(
        Long id,
        Long fileId,
        String status,
        LocalDateTime expiresAt,
        Long expectedSize,
        String expectedHash,
        Integer baseVersion,
        String chunkingAlgorithm
) {
    public static UploadSessionResponse from(UploadSession session) {
        return new UploadSessionResponse(
                session.getId(),
                session.getFile().getId(),
                session.getStatus().name(),
                session.getExpiresAt(),
                session.getExpectedFileSize(),
                session.getExpectedFileHash(),
                session.getBaseVersion(),
                session.getChunkingAlgorithm()
        );
    }
}
