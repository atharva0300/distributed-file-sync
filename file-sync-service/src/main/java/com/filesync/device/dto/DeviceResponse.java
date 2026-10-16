package com.filesync.device.dto;

import com.filesync.device.entity.Device;
import com.filesync.device.entity.DeviceStatus;
import com.filesync.device.entity.DeviceType;

import java.time.LocalDateTime;

public record DeviceResponse(
        Long id,
        String deviceName,
        DeviceType deviceType,
        String deviceToken,
        DeviceStatus status,
        LocalDateTime lastSeenAt,
        LocalDateTime createdAt
) {
    public static DeviceResponse from(Device device) {
        return new DeviceResponse(
                device.getId(),
                device.getDeviceName(),
                device.getDeviceType(),
                null, // Do not expose the hash
                device.getStatus(),
                device.getLastSeenAt(),
                device.getCreatedAt()
        );
    }

    public static DeviceResponse withToken(Device device, String clearTextToken) {
        return new DeviceResponse(
                device.getId(),
                device.getDeviceName(),
                device.getDeviceType(),
                clearTextToken,
                device.getStatus(),
                device.getLastSeenAt(),
                device.getCreatedAt()
        );
    }
}
