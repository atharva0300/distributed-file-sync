package com.filesync.chunk.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "chunks")
@Getter
@Setter
@NoArgsConstructor
public class Chunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hash", nullable = false, unique = true, length = 255)
    private String hash;

    @Column(name = "size", nullable = false)
    private Long size;

    @Column(name = "storage_key", nullable = false, length = 255)
    private String storageKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @org.hibernate.annotations.UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleting_at")
    private LocalDateTime deletingAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ChunkStatus status = ChunkStatus.ACTIVE;

    public Chunk(String hash, Long size, String storageKey) {
        this.hash = hash;
        this.size = size;
        this.storageKey = storageKey;
        this.createdAt = LocalDateTime.now();
    }
}
