package com.filesync.file.controller;

import com.filesync.common.response.ApiResponse;
import com.filesync.device.service.DeviceService;
import com.filesync.file.dto.*;
import com.filesync.file.entity.FileEntry;
import com.filesync.file.service.FileService;
import com.filesync.security.UserPrincipal;
import com.filesync.client.StorageServiceClient;
import com.filesync.version.entity.FileVersion;
import com.filesync.version.service.VersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@Tag(name = "Files", description = "File and folder management")
@SecurityRequirement(name = "bearerAuth")
public class FileController {

    private final FileService fileService;
    private final VersionService versionService;
    private final StorageServiceClient storageClient;
    private final DeviceService deviceService;

    @PostMapping("/folder")
    @Operation(summary = "Create a folder")
    public ResponseEntity<ApiResponse<FileResponse>> createFolder(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateFolderRequest request) {
        FileResponse response = fileService.createFolder(principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @PostMapping("/upload/init")
    @Operation(summary = "Initialize a chunked file upload — returns fileId for subsequent chunk uploads")
    public ResponseEntity<ApiResponse<FileResponse>> initUpload(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody InitUploadRequest request) {
        FileResponse response = fileService.initUpload(principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping
    @Operation(summary = "List files (pass parentId for folder contents, omit for root)")
    public ResponseEntity<ApiResponse<List<FileResponse>>> listFiles(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) Long parentId) {
        List<FileResponse> files = fileService.listFiles(principal.getId(), parentId);
        return ResponseEntity.ok(ApiResponse.success(files));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get file metadata")
    public ResponseEntity<ApiResponse<FileResponse>> getFile(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        FileResponse response = fileService.getFile(principal.getId(), id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft-delete a file or folder")
    public ResponseEntity<ApiResponse<Void>> deleteFile(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        fileService.deleteFile(principal.getId(), id);
        return ResponseEntity.ok(ApiResponse.success("File deleted", null));
    }

    @PostMapping("/rename")
    @Operation(summary = "Rename a file or folder")
    public ResponseEntity<ApiResponse<FileResponse>> renameFile(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody RenameRequest request) {
        FileResponse response = fileService.renameFile(principal.getId(), request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/move")
    @Operation(summary = "Move a file or folder")
    public ResponseEntity<ApiResponse<FileResponse>> moveFile(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody MoveRequest request) {
        FileResponse response = fileService.moveFile(principal.getId(), request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Download a file by reconstructing it from its chunks in order.
     *
     * This demonstrates chunk reassembly: the server reads each chunk from
     * content-addressable storage in chunk_index order and streams them
     * sequentially to the client.
     */
    @GetMapping("/{id}/download")
    @Operation(summary = "Download a file — assembled from chunks server-side")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> downloadFile(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) throws IOException {

        DownloadManifest manifest = fileService.getDownloadManifest(principal.getId(), id);

        if (manifest.chunkHashes().isEmpty()) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + manifest.fileName() + "\"")
                    .header(HttpHeaders.CONTENT_LENGTH, "0")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(outputStream -> {});
        }

        org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody responseBody = outputStream -> {
            for (String hash : manifest.chunkHashes()) {
                try {
                    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                    try (InputStream is = storageClient.readChunk(hash);
                         java.security.DigestInputStream dis = new java.security.DigestInputStream(is, digest)) {
                        
                        dis.transferTo(outputStream);
                        
                        StringBuilder hexString = new StringBuilder();
                        for (byte b : digest.digest()) {
                            hexString.append(String.format("%02x", b));
                        }
                        String calculatedHash = hexString.toString();
                        
                        if (!calculatedHash.equalsIgnoreCase(hash)) {
                            throw new IOException("Data corruption detected: chunk hash mismatch. Expected: " + hash + ", Got: " + calculatedHash);
                        }
                    }
                } catch (java.security.NoSuchAlgorithmException e) {
                    throw new RuntimeException("SHA-256 algorithm not available", e);
                }
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + manifest.fileName() + "\"")
                .header(HttpHeaders.CONTENT_LENGTH,
                        manifest.fileSize() != null ? String.valueOf(manifest.fileSize()) : "")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(responseBody);
    }

    @GetMapping("/{id}/versions")
    @Operation(summary = "Get version history for a file")
    public ResponseEntity<ApiResponse<List<FileVersionResponse>>> getVersions(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        // Verify ownership
        fileService.getFileAndVerifyOwnership(principal.getId(), id);
        List<FileVersionResponse> versions = versionService.listVersions(id).stream()
                .map(FileVersionResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.success(versions));
    }

    @PostMapping("/{id}/restore/{versionNumber}")
    @Operation(summary = "Restore a file to a previous version — creates a new forward version")
    public ResponseEntity<ApiResponse<FileVersionResponse>> restoreVersion(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @PathVariable int versionNumber,
            @RequestParam Long deviceId) {

        deviceService.getDeviceAndVerifyOwnership(deviceId, principal.getId());

        var device = deviceService.listDevices(principal.getId())
                .stream().filter(d -> d.id().equals(deviceId)).findFirst().orElseThrow();

        FileVersion version = versionService.restoreVersion(
                id, versionNumber, principal.getId(), deviceId, device.deviceName());

        return ResponseEntity.ok(ApiResponse.success(FileVersionResponse.from(version)));
    }
}
