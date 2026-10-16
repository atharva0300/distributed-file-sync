package com.filesync.device.dto;

import com.filesync.device.entity.DeviceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateDeviceRequest(
        @NotBlank String deviceName,
        @NotNull DeviceType deviceType
) {}
