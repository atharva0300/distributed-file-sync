package com.filesync.upload.entity;

import com.filesync.device.entity.Device;
import com.filesync.file.entity.FileEntry;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "upload_sessions")
@Getter
@Setter
@NoArgsConstructor
public class UploadSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    private FileEntry file;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    @Column(name = "target_version", nullable = false)
    private int targetVersion;

    @Column(name = "expected_file_size", nullable = false)
    private Long expectedFileSize;

    @Column(name = "expected_file_hash", nullable = false, length = 255)
    private String expectedFileHash;

    @Column(name = "base_version")
    private Integer baseVersion;

    @Column(name = "chunk_size", nullable = false)
    private Long chunkSize;

    /**
     * Identifies the chunking algorithm used for this upload session.
     * Values: "CDC" (content-defined chunking).
     * Defaults to "CDC".
     */
    @Column(name = "chunking_algorithm", nullable = false, length = 10)
    private String chunkingAlgorithm = "CDC";

    /**
     * Total number of chunks expected in this upload session.
     *
     * <p>For CDC sessions: set after the client sends the delta manifest
     * ({@code POST /api/upload/delta}). NULL means delta not yet computed.</p>
     */
    @Column(name = "total_chunks")
    private Integer totalChunks;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private UploadSessionStatus status = UploadSessionStatus.INITIATED;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    /**
     * Full constructor used by UploadService when creating a new session.
     *
     * @param chunkingAlgorithm "CDC"
     */
    public UploadSession(FileEntry file, Device device, Integer baseVersion, int targetVersion,
                         Long expectedFileSize, String expectedFileHash, Long chunkSize,
                         LocalDateTime expiresAt, String chunkingAlgorithm) {
        this.file = file;
        this.device = device;
        this.baseVersion = baseVersion;
        this.targetVersion = targetVersion;
        this.expectedFileSize = expectedFileSize;
        this.expectedFileHash = expectedFileHash;
        this.chunkSize = chunkSize;
        this.expiresAt = expiresAt;
        this.chunkingAlgorithm = chunkingAlgorithm != null ? chunkingAlgorithm : "CDC";
    }

    /**
     * Legacy constructor (defaults chunkingAlgorithm to "FIXED").
     * Used by existing code paths that pre-date CDC.
     */
    public UploadSession(FileEntry file, Device device, Integer baseVersion, int targetVersion,
                         Long expectedFileSize, String expectedFileHash, Long chunkSize,
                         LocalDateTime expiresAt) {
        this(file, device, baseVersion, targetVersion, expectedFileSize, expectedFileHash,
                chunkSize, expiresAt, "CDC");
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
