package com.filesync.sync.entity;

import com.filesync.auth.entity.User;
import com.filesync.device.entity.Device;
import com.filesync.file.entity.FileEntry;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "sync_operations")
@Getter
@Setter
@NoArgsConstructor
public class SyncOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_id")
    private FileEntry file;

    @Column(name = "operation_type", nullable = false, length = 50)
    private String operationType;

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(name = "base_version")
    private Integer baseVersion;

    @Column(nullable = false, length = 50)
    private String status = "PENDING"; // PENDING, PROCESSING, COMPLETED, CONFLICT, FAILED

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "available_at", nullable = false)
    private LocalDateTime availableAt = LocalDateTime.now();

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "locked_by", length = 255)
    private String lockedBy;

    @Column(name = "lease_token", length = 36)
    private String leaseToken;

    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "client_operation_id", length = 255)
    private String clientOperationId;

    @Column(name = "upload_session_id")
    private Long uploadSessionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public SyncOperation(Device device, User user, FileEntry file, String operationType, String payload, Integer baseVersion, String clientOperationId, Long uploadSessionId) {
        this.device = device;
        this.user = user;
        this.file = file;
        this.operationType = operationType;
        this.payload = payload;
        this.baseVersion = baseVersion;
        this.clientOperationId = clientOperationId;
        this.uploadSessionId = uploadSessionId;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
