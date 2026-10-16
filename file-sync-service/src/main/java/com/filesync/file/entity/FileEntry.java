package com.filesync.file.entity;

import com.filesync.auth.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Represents a file OR a folder in the user's virtual filesystem.
 *
 * Key design decisions:
 * - Self-referential parent relationship for folder hierarchy
 * - @Version for optimistic locking: concurrent modifications increment this
 *   field, and Hibernate throws ObjectOptimisticLockingFailureException if
 *   two transactions try to update the same version simultaneously.
 * - currentVersion tracks the logical file version in MongoDB
 * - uploadComplete flag prevents incomplete files from being visible
 */
@Entity
@Table(name = "files")
@Getter
@Setter
@NoArgsConstructor
public class FileEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_folder_id")
    private FileEntry parentFolder;

    @Column(nullable = false, length = 255)
    private String name;



    @Column(name = "current_version", nullable = false)
    private int currentVersion = 0;

    @Column(name = "file_hash", length = 64)
    private String fileHash;

    @Column(name = "file_size", nullable = false)
    private Long fileSize = 0L;

    @Column(name = "is_folder", nullable = false)
    private boolean isFolder = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_version_id")
    private com.filesync.version.entity.FileVersion currentVersionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(nullable = false)
    private boolean deleted = false;

    /**
     * Hibernate optimistic locking column.
     * Each successful save increments this value.
     * On concurrent update, Hibernate detects stale read → throws
     * ObjectOptimisticLockingFailureException → caught as conflict.
     */
    @jakarta.persistence.Version
    @Column(nullable = false)
    private long version = 0;

    public FileEntry(User user, FileEntry parentFolder, String name, boolean isFolder) {
        this.user = user;
        this.parentFolder = parentFolder;
        this.name = name;
        this.isFolder = isFolder;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    @Transient
    public String getPath() {
        if (parentFolder == null) {
            return "/" + name;
        }
        return parentFolder.getPath() + "/" + name;
    }

    @Transient
    public boolean isUploadComplete() {
        return isFolder || currentVersion > 0;
    }

    @Transient
    public SyncStatus getSyncStatus() {
        return isUploadComplete() ? SyncStatus.SYNCED : SyncStatus.PENDING;
    }
}
