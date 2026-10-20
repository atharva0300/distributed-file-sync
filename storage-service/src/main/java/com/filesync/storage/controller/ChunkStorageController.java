package com.filesync.storage.controller;

import com.filesync.storage.dto.ChunkExistsResponse;
import com.filesync.storage.dto.ChunkStorageResponse;
import com.filesync.storage.service.FileStorageService;
import com.filesync.storage.util.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class ChunkStorageController {

    private final FileStorageService storageService;

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> healthCheck() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "storage-service"
        ));
    }

    @PostMapping(value = "/chunks/{hash}", consumes = {MediaType.APPLICATION_OCTET_STREAM_VALUE, MediaType.ALL_VALUE})
    public ResponseEntity<ChunkStorageResponse> storeChunkOctetStream(
            @PathVariable String hash,
            jakarta.servlet.http.HttpServletRequest request) throws IOException {
        HashUtil.validateHash(hash);
        
        long contentLength = request.getContentLengthLong();
        if (contentLength <= 0) {
            throw new IllegalArgumentException("Content-Length must be provided and > 0 for streaming");
        }

        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }

        try (java.security.DigestInputStream dis = new java.security.DigestInputStream(request.getInputStream(), digest)) {
            // Stream directly to storage service (S3/local)
            String storageKey = storageService.storeChunk(hash, dis, contentLength);
            
            // Drain any remaining bytes to ensure the full stream is hashed
            // (e.g. if storage service skipped storing because chunk already exists)
            dis.transferTo(java.io.OutputStream.nullOutputStream());
            
            // Verify hash after stream is consumed
            byte[] calculatedHashBytes = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : calculatedHashBytes) {
                hexString.append(String.format("%02x", b));
            }
            String calculatedHash = hexString.toString();

            if (!calculatedHash.equalsIgnoreCase(hash)) {
                // Hash mismatch after streaming -> clean up the invalid chunk
                try {
                    storageService.deleteChunk(hash);
                } catch (Exception e) {
                    log.error("Failed to cleanup invalid chunk after hash mismatch: {}", hash, e);
                }
                throw new IllegalArgumentException("SHA-256 hash mismatch. Expected: " + hash + ", Calculated: " + calculatedHash);
            }

            ChunkStorageResponse response = new ChunkStorageResponse(
                    hash,
                    storageKey,
                    false, // Deduplication check removed; we always put and it's idempotent
                    contentLength
            );

            return ResponseEntity.ok(response);
        }
    }

    @GetMapping("/chunks/{hash}/exists")
    public ResponseEntity<ChunkExistsResponse> checkChunkExists(@PathVariable String hash) {
        HashUtil.validateHash(hash);
        boolean exists = storageService.chunkExists(hash);
        return ResponseEntity.ok(new ChunkExistsResponse(hash, exists));
    }

    @GetMapping(value = "/chunks/{hash}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> readChunk(@PathVariable String hash) {
        HashUtil.validateHash(hash);
        InputStream originalStream = storageService.readChunk(hash);

        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }

        java.security.DigestInputStream dis = new java.security.DigestInputStream(originalStream, digest);
        
        InputStream validatingStream = new InputStream() {
            @Override
            public int read() throws IOException {
                int b = dis.read();
                if (b == -1) verifyHash();
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int bytesRead = dis.read(b, off, len);
                if (bytesRead == -1) verifyHash();
                return bytesRead;
            }

            @Override
            public void close() throws IOException {
                dis.close();
            }

            private void verifyHash() throws IOException {
                byte[] calculatedHashBytes = digest.digest();
                StringBuilder hexString = new StringBuilder();
                for (byte b : calculatedHashBytes) {
                    hexString.append(String.format("%02x", b));
                }
                String calculatedHash = hexString.toString();
                if (!calculatedHash.equalsIgnoreCase(hash)) {
                    log.error("Download hash mismatch! Expected: {}, Calculated: {}", hash, calculatedHash);
                    throw new IOException("Data corruption detected during download. Hash mismatch.");
                }
            }
        };

        InputStreamResource resource = new InputStreamResource(validatingStream);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + hash + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @DeleteMapping("/chunks/{hash}")
    public ResponseEntity<Void> deleteChunk(@PathVariable String hash) {
        HashUtil.validateHash(hash);
        storageService.deleteChunk(hash);
        return ResponseEntity.noContent().build();
    }
}

