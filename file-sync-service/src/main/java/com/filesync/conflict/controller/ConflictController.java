package com.filesync.conflict.controller;

import com.filesync.common.response.ApiResponse;
import com.filesync.conflict.dto.ConflictResolutionRequest;
import com.filesync.conflict.dto.ConflictResponse;
import com.filesync.conflict.service.ConflictService;
import com.filesync.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/conflicts")
@RequiredArgsConstructor
@Tag(name = "Conflicts", description = "Conflict detection and resolution")
@SecurityRequirement(name = "bearerAuth")
public class ConflictController {

    private final ConflictService conflictService;

    @GetMapping
    @Operation(summary = "List all detected conflicts for the authenticated user")
    public ResponseEntity<ApiResponse<List<ConflictResponse>>> getConflicts(
            @AuthenticationPrincipal UserPrincipal principal) {
        List<ConflictResponse> conflicts = conflictService.getConflicts(principal.getId());
        return ResponseEntity.ok(ApiResponse.success(conflicts));
    }

    @PostMapping("/{operationId}/resolve")
    @Operation(summary = "Resolve a conflict — KEEP_SERVER | KEEP_LOCAL | CREATE_CONFLICT_COPY")
    public ResponseEntity<ApiResponse<String>> resolveConflict(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long operationId,
            @Valid @RequestBody ConflictResolutionRequest request) {
        String result = conflictService.resolveConflict(principal.getId(), operationId, request);
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
