package com.filesync.device.entity;

import com.filesync.auth.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "devices")
@Getter
@Setter
@NoArgsConstructor
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "device_name", nullable = false, length = 255)
    private String deviceName;

    @Column(name = "device_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private DeviceType deviceType;

    @Column(name = "device_token_hash", length = 255)
    private String deviceTokenHash;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private DeviceStatus status = DeviceStatus.OFFLINE;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Device(User user, String deviceName, DeviceType deviceType, String deviceTokenHash) {
        this.user = user;
        this.deviceName = deviceName;
        this.deviceType = deviceType;
        this.deviceTokenHash = deviceTokenHash;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
