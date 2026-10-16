package com.filesync.device.repository;

import com.filesync.device.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {
    List<Device> findByUserId(Long userId);
    Optional<Device> findByIdAndUserId(Long id, Long userId);
    boolean existsByDeviceTokenHash(String deviceTokenHash);
}
