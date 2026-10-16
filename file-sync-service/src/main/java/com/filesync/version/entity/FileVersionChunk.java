package com.filesync.version.entity;

import com.filesync.chunk.entity.Chunk;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "file_version_chunks")
@Getter
@Setter
@NoArgsConstructor
public class FileVersionChunk {

    @EmbeddedId
    private FileVersionChunkId id = new FileVersionChunkId();

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("versionId")
    @JoinColumn(name = "version_id", nullable = false)
    private FileVersion fileVersion;

    @Column(name = "chunk_index", insertable = false, updatable = false)
    private Integer chunkIndex;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chunk_id", nullable = false)
    private Chunk chunk;

    @Column(name = "chunk_size", nullable = false)
    private Long chunkSize;

    public FileVersionChunk(FileVersion fileVersion, Integer chunkIndex, Chunk chunk, Long chunkSize) {
        this.id = fileVersion != null ? new FileVersionChunkId(fileVersion.getId(), chunkIndex) : new FileVersionChunkId(null, chunkIndex);
        this.fileVersion = fileVersion;
        this.chunkIndex = chunkIndex;
        this.chunk = chunk;
        this.chunkSize = chunkSize;
    }
}
