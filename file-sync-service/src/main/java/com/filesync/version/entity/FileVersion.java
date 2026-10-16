package com.filesync.version.entity;

import com.filesync.device.entity.Device;
import com.filesync.file.entity.FileEntry;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "file_versions")
@Getter
@Setter
@NoArgsConstructor
public class FileVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    private FileEntry file;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "file_hash", nullable = false, length = 255)
    private String fileHash;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_device_id")
    private Device createdByDevice;

    @Column(name = "change_type", nullable = false, length = 50)
    private String changeType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "upload_session_id")
    private Long uploadSessionId;

    /**
     * Identifies which chunking algorithm produced the chunks in this version.
     * Values: "CDC" (content-defined chunking via Rabin fingerprint).
     *
     * <p>Defaults to "CDC". This value is recorded alongside the chunks and is used to
     * interpret the chunk boundaries during download/reassembly.</p>
     */
    @Column(name = "chunking_algorithm", nullable = false, length = 10)
    private String chunkingAlgorithm = "CDC";

    @OneToMany(mappedBy = "fileVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("chunkIndex ASC")
    private List<FileVersionChunk> chunks = new ArrayList<>();

    public FileVersion(FileEntry file, int versionNumber, String fileHash, Long fileSize,
                       Device createdByDevice, String changeType) {
        this(file, versionNumber, fileHash, fileSize, createdByDevice, changeType, null);
    }

    public FileVersion(FileEntry file, int versionNumber, String fileHash, Long fileSize,
                       Device createdByDevice, String changeType, Long uploadSessionId) {
        this(file, versionNumber, fileHash, fileSize, createdByDevice, changeType, uploadSessionId, "CDC");
    }

    /**
     * Full constructor with chunkingAlgorithm.
     */
    public FileVersion(FileEntry file, int versionNumber, String fileHash, Long fileSize,
                       Device createdByDevice, String changeType, Long uploadSessionId,
                       String chunkingAlgorithm) {
        this.file = file;
        this.versionNumber = versionNumber;
        this.fileHash = fileHash;
        this.fileSize = fileSize;
        this.createdByDevice = createdByDevice;
        this.changeType = changeType;
        this.uploadSessionId = uploadSessionId;
        this.chunkingAlgorithm = chunkingAlgorithm != null ? chunkingAlgorithm : "CDC";
    }
}
