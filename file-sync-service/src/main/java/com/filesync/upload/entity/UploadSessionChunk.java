package com.filesync.upload.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.filesync.chunk.entity.Chunk;

import java.time.LocalDateTime;

@Entity
@Table(name = "upload_session_chunks", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"upload_session_id", "chunk_index"})
})
@Getter
@Setter
@NoArgsConstructor
public class UploadSessionChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "upload_session_id", nullable = false)
    private UploadSession uploadSession;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chunk_id", nullable = false)
    private Chunk chunk;

    @Column(name = "chunk_size", nullable = false)
    private Long chunkSize;

    @Column(nullable = false, length = 50)
    private String status = "UPLOADED"; // UPLOADED

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public UploadSessionChunk(UploadSession uploadSession, int chunkIndex, Chunk chunk, Long chunkSize) {
        this.uploadSession = uploadSession;
        this.chunkIndex = chunkIndex;
        this.chunk = chunk;
        this.chunkSize = chunkSize;
    }
}
