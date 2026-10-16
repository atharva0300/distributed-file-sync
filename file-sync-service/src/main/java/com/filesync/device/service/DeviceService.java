package com.filesync.device.service;

import com.filesync.auth.entity.User;
import com.filesync.auth.repository.UserRepository;
import com.filesync.common.exception.ForbiddenException;
import com.filesync.common.exception.NotFoundException;
import com.filesync.device.dto.CreateDeviceRequest;
import com.filesync.device.dto.DeviceResponse;
import com.filesync.device.entity.Device;
import com.filesync.device.entity.DeviceStatus;
import com.filesync.device.repository.DeviceRepository;
import com.filesync.common.util.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;

    @Transactional
    public DeviceResponse registerDevice(Long userId, CreateDeviceRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));

        String deviceToken = UUID.randomUUID().toString();
        String deviceTokenHash = HashUtil.sha256(deviceToken);
        Device device = new Device(user, request.deviceName(), request.deviceType(), deviceTokenHash);
        deviceRepository.save(device);

        log.info("DEVICE_REGISTERED: userId={}, deviceId={}, name={}", userId, device.getId(), device.getDeviceName());
        return DeviceResponse.withToken(device, deviceToken);
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> listDevices(Long userId) {
        return deviceRepository.findByUserId(userId)
                .stream()
                .map(DeviceResponse::from)
                .toList();
    }

    @Transactional
    public DeviceResponse updateStatus(Long userId, Long deviceId, DeviceStatus status) {
        Device device = deviceRepository.findByIdAndUserId(deviceId, userId)
                .orElseThrow(() -> new NotFoundException("Device not found: " + deviceId));

        device.setStatus(status);
        device.setLastSeenAt(status == DeviceStatus.ONLINE ? LocalDateTime.now() : device.getLastSeenAt());
        deviceRepository.save(device);

        log.info("DEVICE_STATUS_UPDATED: deviceId={}, status={}", deviceId, status);
        return DeviceResponse.from(device);
    }

    @Transactional
    public void deleteDevice(Long userId, Long deviceId) {
        Device device = deviceRepository.findByIdAndUserId(deviceId, userId)
                .orElseThrow(() -> new NotFoundException("Device not found: " + deviceId));

        deviceRepository.delete(device);
        log.info("DEVICE_DELETED: deviceId={}, userId={}", deviceId, userId);
    }

    @Transactional(readOnly = true)
    public Device getDeviceAndVerifyOwnership(Long deviceId, Long userId) {
        return deviceRepository.findByIdAndUserId(deviceId, userId)
                .orElseThrow(() -> new ForbiddenException("Device " + deviceId + " does not belong to user " + userId));
    }
}
