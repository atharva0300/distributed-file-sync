package com.filesync.device.controller;

import com.filesync.common.response.ApiResponse;
import com.filesync.device.dto.CreateDeviceRequest;
import com.filesync.device.dto.DeviceResponse;
import com.filesync.device.entity.DeviceStatus;
import com.filesync.device.service.DeviceService;
import com.filesync.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
@Tag(name = "Devices", description = "Device management — register, list, status toggle")
@SecurityRequirement(name = "bearerAuth")
public class DeviceController {

    private final DeviceService deviceService;

    @PostMapping
    @Operation(summary = "Register a new device for the authenticated user")
    public ResponseEntity<ApiResponse<DeviceResponse>> registerDevice(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateDeviceRequest request) {
        DeviceResponse response = deviceService.registerDevice(principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping
    @Operation(summary = "List all devices for the authenticated user")
    public ResponseEntity<ApiResponse<List<DeviceResponse>>> listDevices(
            @AuthenticationPrincipal UserPrincipal principal) {
        List<DeviceResponse> devices = deviceService.listDevices(principal.getId());
        return ResponseEntity.ok(ApiResponse.success(devices));
    }

    @PutMapping("/{deviceId}/status")
    @Operation(summary = "Toggle device online/offline status (for sync simulation)")
    public ResponseEntity<ApiResponse<DeviceResponse>> updateStatus(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long deviceId,
            @RequestParam DeviceStatus status) {
        DeviceResponse response = deviceService.updateStatus(principal.getId(), deviceId, status);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @DeleteMapping("/{deviceId}")
    @Operation(summary = "Delete a device")
    public ResponseEntity<ApiResponse<Void>> deleteDevice(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long deviceId) {
        deviceService.deleteDevice(principal.getId(), deviceId);
        return ResponseEntity.ok(ApiResponse.success("Device deleted", null));
    }
}
