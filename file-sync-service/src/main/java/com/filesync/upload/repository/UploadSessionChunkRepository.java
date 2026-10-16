package com.filesync.upload.repository;

import com.filesync.upload.entity.UploadSessionChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UploadSessionChunkRepository extends JpaRepository<UploadSessionChunk, Long> {
    List<UploadSessionChunk> findByUploadSessionIdOrderByChunkIndexAsc(Long uploadSessionId);
    boolean existsByUploadSessionIdAndChunkIndex(Long uploadSessionId, int chunkIndex);
    java.util.Optional<UploadSessionChunk> findByUploadSessionIdAndChunkIndex(Long uploadSessionId, int chunkIndex);
}
