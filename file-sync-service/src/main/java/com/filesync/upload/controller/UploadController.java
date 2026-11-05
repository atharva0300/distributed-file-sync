package com.filesync.upload.controller;

import com.filesync.common.response.ApiResponse;
import com.filesync.security.UserPrincipal;
import com.filesync.upload.dto.DeltaRequest;
import com.filesync.upload.service.UploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
@Tag(name = "Upload", description = "Resumable chunked file upload and delta sync")
@SecurityRequirement(name = "bearerAuth")
public class UploadController {

    private final UploadService uploadService;

    @PostMapping("/init")
    @Operation(summary = "Initialize an upload session")
    public ResponseEntity<ApiResponse<com.filesync.upload.dto.UploadInitResponse>> initUpload(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam Long fileId,
            @RequestParam Long deviceId,
            @RequestParam(required = false) Integer baseVersion,
            @RequestParam Long expectedSize,
            @RequestParam String expectedHash) {
        com.filesync.upload.dto.UploadInitResponse response = uploadService.initUpload(principal.getId(), fileId, deviceId, baseVersion, expectedSize, expectedHash);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @PostMapping("/{sessionId}/chunks/{chunkIndex}")
    @Operation(summary = "Upload a single chunk for a session")
    public ResponseEntity<ApiResponse<Void>> uploadChunk(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long sessionId,
            @PathVariable int chunkIndex,
            @RequestParam String clientHash,
            @RequestBody(required = false) byte[] rawData,
            @RequestParam(value = "data", required = false) org.springframework.web.multipart.MultipartFile multipartData) throws java.io.IOException {
        byte[] data = (rawData != null && rawData.length > 0)
                ? rawData
                : (multipartData != null ? multipartData.getBytes() : new byte[0]);
        uploadService.uploadChunk(principal.getId(), sessionId, chunkIndex, clientHash, data);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/{sessionId}/chunks/{chunkIndex}/link")
    @Operation(summary = "Link an already-stored chunk to this index without re-uploading its bytes",
            description = "Succeeds only if the server already has an ACTIVE chunk with this hash+size " +
                    "(e.g. unchanged from the file's previous version, or deduplicated against another " +
                    "file). Otherwise fails and the client must call uploadChunk() instead.")
    public ResponseEntity<ApiResponse<Void>> linkExistingChunk(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long sessionId,
            @PathVariable int chunkIndex,
            @RequestParam String clientHash,
            @RequestParam long size) {
        uploadService.linkExistingChunk(principal.getId(), sessionId, chunkIndex, clientHash, size);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping("/{sessionId}")
    @Operation(summary = "Get upload session status")
    public ResponseEntity<ApiResponse<com.filesync.upload.dto.UploadSessionResponse>> getSession(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long sessionId) {
        return ResponseEntity.ok(ApiResponse.success(uploadService.getSession(principal.getId(), sessionId)));
    }

    @GetMapping("/{sessionId}/missing")
    @Operation(summary = "Get missing chunk indices for a session")
    public ResponseEntity<ApiResponse<List<Integer>>> getMissingChunks(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long sessionId) {
        return ResponseEntity.ok(ApiResponse.success(uploadService.getMissingChunks(principal.getId(), sessionId)));
    }

    @PostMapping("/{sessionId}/abort")
    @Operation(summary = "Abort an active upload session")
    public ResponseEntity<ApiResponse<Void>> abortSession(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long sessionId) {
        uploadService.abortSession(principal.getId(), sessionId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/delta")
    @Operation(summary = "Compute delta (missing chunks) for a file version",
            description = "request.sessionId must be the session returned by /upload/init — this is also " +
                    "how the session's totalChunks gets recorded, which completeUpload() requires for CDC.")
    public ResponseEntity<ApiResponse<List<String>>> computeDelta(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam Long fileId,
            @jakarta.validation.Valid @RequestBody DeltaRequest request) {
        List<String> missingChunks = uploadService.computeDelta(
                principal.getId(), fileId, request.sessionId(), request.baseVersion(),
                request.expectedFileSize(), request.clientChunks());
        return ResponseEntity.ok(ApiResponse.success(missingChunks));
    }

    @GetMapping("/config")
    @Operation(summary = "Get upload configuration")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> getConfig(
            @org.springframework.beans.factory.annotation.Autowired com.filesync.chunking.ChunkingStrategyFactory factory) {
        return ResponseEntity.ok(ApiResponse.success(java.util.Map.of(
                "chunkingMode", "CDC",
                "targetChunkSize", factory.getTargetSize(),
                "minChunkSize", factory.getMinSize(),
                "maxChunkSize", factory.getMaxSize(),
                "chunkSize", factory.getTargetSize() // for backward compatibility
        )));
    }
}
