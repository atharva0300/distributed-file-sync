package com.filesync.conflict.entity;

import com.filesync.file.entity.FileEntry;
import com.filesync.sync.entity.SyncOperation;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "conflicts")
@Getter
@Setter
@NoArgsConstructor
public class Conflict {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operation_id", nullable = false)
    private SyncOperation operation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    private FileEntry file;

    @Column(name = "server_version", nullable = false)
    private int serverVersion;

    @Column(name = "client_base_version", nullable = false)
    private int clientBaseVersion;

    @Column(nullable = false, length = 50)
    private String status = "OPEN"; // OPEN, RESOLVED

    @Column(length = 50)
    private String resolution;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    public Conflict(SyncOperation operation, FileEntry file, int serverVersion, int clientBaseVersion) {
        this.operation = operation;
        this.file = file;
        this.serverVersion = serverVersion;
        this.clientBaseVersion = clientBaseVersion;
    }
}
