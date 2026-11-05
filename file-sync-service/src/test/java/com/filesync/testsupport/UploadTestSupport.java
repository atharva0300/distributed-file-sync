package com.filesync.testsupport;

import com.filesync.upload.dto.DeltaRequest;
import com.filesync.upload.service.UploadService;

import java.util.List;

/** Drives the real client protocol for a single-chunk file: delta (records totalChunks) then chunk upload. */
public final class UploadTestSupport {

    private UploadTestSupport() {}

    public static void uploadSingleChunk(UploadService uploadService, Long userId, Long fileId, Long sessionId,
                                         Integer baseVersion, String hash, byte[] data) {
        uploadService.computeDelta(userId, fileId, sessionId, baseVersion, (long) data.length,
                List.of(new DeltaRequest.ChunkManifestEntry(0, hash, (long) data.length)));
        uploadService.uploadChunk(userId, sessionId, 0, hash, data);
    }
}
