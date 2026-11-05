package com.filesync.upload.service;

import com.filesync.chunk.entity.Chunk;
import com.filesync.chunk.entity.ChunkStatus;
import com.filesync.chunk.repository.ChunkRepository;
import com.filesync.upload.entity.UploadSession;
import com.filesync.upload.entity.UploadSessionChunk;
import com.filesync.version.entity.FileVersionChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Reusable server-side validation of an upload session candidate.
 *
 * Called by:
 *   - UploadService.completeUpload()
 *   - ConflictService (KEEP_LOCAL and CREATE_CONFLICT_COPY paths)
 *
 * Validates all invariants required before a FileVersion can be committed.
 * Chunk count comes from the manifest recorded by the delta step (CDC chunk boundaries are content-defined).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CandidateValidator {

    private final ChunkRepository chunkRepository;

    public List<FileVersionChunk> validate(UploadSession session, List<UploadSessionChunk> sessionChunks) {
        long expectedFileSize = session.getExpectedFileSize();
        String expectedHash = session.getExpectedFileHash();
        long chunkSize = session.getChunkSize();

        // 1. expectedFileSize >= 0
        if (expectedFileSize < 0) {
            throw new IllegalStateException("expectedFileSize must be >= 0, got: " + expectedFileSize);
        }

        // 2. expectedFileHash is valid SHA-256 (64-char lowercase hex)
        if (expectedHash == null || !expectedHash.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("expectedFileHash is not a valid SHA-256 hex string: " + expectedHash);
        }

        // 3. chunkSize > 0
        if (chunkSize <= 0) {
            throw new IllegalStateException("chunkSize must be > 0, got: " + chunkSize);
        }

        // Zero-byte file has 0 chunks
        if (expectedFileSize == 0) {
            if (!sessionChunks.isEmpty()) {
                throw new IllegalStateException("Zero-byte file expects 0 chunks, got " + sessionChunks.size());
            }
            return List.of();
        }

        // 4. Expected chunk count
        if (session.getTotalChunks() == null) {
            throw new IllegalStateException("Session totalChunks is not set (delta was never computed)");
        }
        int expectedChunkCount = session.getTotalChunks();

        if (sessionChunks.size() != expectedChunkCount) {
            throw new IllegalStateException(
                "Chunk count mismatch: expected " + expectedChunkCount + " but got " + sessionChunks.size());
        }

        // 5-8. Validate indices: all present, no duplicates, contiguous from 0
        List<UploadSessionChunk> sorted = new ArrayList<>(sessionChunks);
        sorted.sort((a, b) -> Integer.compare(a.getChunkIndex(), b.getChunkIndex()));

        if (sorted.get(0).getChunkIndex() != 0) {
            throw new IllegalStateException("Chunk indices must start at 0, first was: " + sorted.get(0).getChunkIndex());
        }

        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getChunkIndex() != i) {
                throw new IllegalStateException(
                    "Chunk indices are not contiguous: expected index " + i + " but got " + sorted.get(i).getChunkIndex());
            }
        }

        // 9-12. Validate each chunk
        long totalSize = 0;
        List<FileVersionChunk> result = new ArrayList<>();

        for (int i = 0; i < sorted.size(); i++) {
            UploadSessionChunk sc = sorted.get(i);

            // Min/max bounds are enforced when the bytes are uploaded; here just sanity-check.
            if (sc.getChunkSize() <= 0) {
                throw new IllegalStateException("Chunk " + i + " has invalid size " + sc.getChunkSize());
            }

            totalSize += sc.getChunkSize();

            long chunkId = sc.getChunk().getId();
            final int finalIndex = i;
            Chunk chunk = chunkRepository.findById(chunkId)
                    .orElseThrow(() -> new IllegalStateException(
                        "Chunk not found in DB for index " + finalIndex + " (chunkId=" + chunkId + ")"));

            if (chunk.getStatus() != ChunkStatus.ACTIVE) {
                throw new IllegalStateException(
                    "Chunk at index " + i + " has status " + chunk.getStatus() + " (must be ACTIVE)");
            }

            result.add(new FileVersionChunk(null, i, chunk, sc.getChunkSize()));
        }

        // 13. Total logical size must equal expectedFileSize
        if (totalSize != expectedFileSize) {
            throw new IllegalStateException(
                "Logical total size " + totalSize + " != expectedFileSize " + expectedFileSize);
        }

        return result;
    }
}
