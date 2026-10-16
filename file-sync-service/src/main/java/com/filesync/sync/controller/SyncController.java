package com.filesync.sync.controller;

import com.filesync.common.response.ApiResponse;
import com.filesync.security.UserPrincipal;
import com.filesync.sync.dto.QueueSyncOperationRequest;
import com.filesync.sync.dto.SyncOperationResponse;
import com.filesync.sync.service.SyncService;
import com.filesync.websocket.SyncNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import java.util.List;

@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
@Tag(name = "Synchronization", description = "Offline sync queue, sync status, and sync logs")
@SecurityRequirement(name = "bearerAuth")
public class SyncController {

    private final SyncService syncService;
    private final SyncNotificationService syncNotificationService;

    @GetMapping(value = "/events", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Subscribe to this user's file/sync events via Server-Sent Events",
            description = "One-directional push: the client never sends anything back over this " +
                    "connection, it just learns when to re-fetch via REST. Browsers cannot attach a " +
                    "custom Authorization header to an EventSource request, so the JWT is also accepted " +
                    "as a ?token= query parameter on this endpoint only.")
    public SseEmitter subscribeToEvents(@AuthenticationPrincipal UserPrincipal principal) {
        return syncNotificationService.subscribe(principal.getId());
    }

    @PostMapping("/operations")
    @Operation(summary = "Queue an operation from an offline device")
    public ResponseEntity<ApiResponse<SyncOperationResponse>> queueOperation(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody QueueSyncOperationRequest request) {
        SyncOperationResponse response = syncService.queueOperation(principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping("/operations")
    @Operation(summary = "Get all sync operations for the authenticated user")
    public ResponseEntity<ApiResponse<Page<SyncOperationResponse>>> getOperations(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String status,
            @PageableDefault(size = 20) Pageable pageable) {

        Page<SyncOperationResponse> ops = (status != null && !status.isBlank())
                ? syncService.getOperationsByStatus(principal.getId(), status, pageable)
                : syncService.getOperationsByStatus(principal.getId(), null, pageable);

        return ResponseEntity.ok(ApiResponse.success(ops));
    }

}
