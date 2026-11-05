package com.filesync.storage.controller;

import com.filesync.storage.config.InternalSignature;
import com.filesync.storage.service.FileStorageService;
import com.filesync.storage.util.HashUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ChunkStorageController Integration Tests")
class ChunkStorageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FileStorageService storageService;

    private static final java.util.concurrent.atomic.AtomicLong NONCE = new java.util.concurrent.atomic.AtomicLong();

    /** Signs the request at build time (after content is set), declaring the hash a real client would. */
    private <T extends org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> T withAuth(T builder) {
        builder.with(request -> {
            String uri = request.getRequestURI();
            String bodyHash = ("POST".equals(request.getMethod()) && uri.startsWith("/internal/chunks/"))
                    ? uri.substring("/internal/chunks/".length())
                    : InternalSignature.EMPTY_BODY_SHA256;
            long ts = System.currentTimeMillis();
            String nonce = String.format("%016x%016x", ts, NONCE.incrementAndGet());
            String sig = InternalSignature.sign("test-internal-key", InternalSignature.canonical(
                    "file-sync-service", request.getMethod(), uri, request.getQueryString(), ts, nonce, bodyHash));
            request.addHeader("X-Service-Id", "file-sync-service");
            request.addHeader("X-Timestamp", String.valueOf(ts));
            request.addHeader("X-Nonce", nonce);
            request.addHeader("X-Content-SHA256", bodyHash);
            request.addHeader("X-Signature", sig);
            return request;
        });
        return builder;
    }

    @Test
    @DisplayName("unsigned requests are rejected")
    void testUnsignedRejected() throws Exception {
        mockMvc.perform(get("/internal/chunks/{hash}/exists", "0".repeat(64)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a bad upload under an existing hash is rejected and the stored chunk survives")
    void testExistingChunkSurvivesBadUpload() throws Exception {
        byte[] content = "valid stored content".getBytes(StandardCharsets.UTF_8);
        String hash = HashUtil.sha256Hex(content);

        mockMvc.perform(withAuth(post("/internal/chunks/{hash}", hash))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(content))
                .andExpect(status().isOk());

        mockMvc.perform(withAuth(post("/internal/chunks/{hash}", hash))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("garbage that is not the chunk".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(withAuth(get("/internal/chunks/{hash}", hash)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(content));
    }

    @Test
    @DisplayName("GET /internal/health returns UP")
    void testHealth() throws Exception {
        mockMvc.perform(get("/internal/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("storage-service"));
    }

    @Test
    @DisplayName("POST /internal/chunks/{hash} stores chunk via octet-stream and checks deduplication")
    void testStoreChunkOctetStream() throws Exception {
        byte[] content = "test chunk payload 12345".getBytes(StandardCharsets.UTF_8);
        String hash = HashUtil.sha256Hex(content);

        // 1. Initial upload
        mockMvc.perform(withAuth(post("/internal/chunks/{hash}", hash))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(content))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hash").value(hash))
                .andExpect(jsonPath("$.alreadyExisted").value(false))
                .andExpect(jsonPath("$.size").value(content.length));

        // 2. Check existence endpoint
        mockMvc.perform(withAuth(get("/internal/chunks/{hash}/exists", hash)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true));

        // 3. Read chunk back
        mockMvc.perform(withAuth(get("/internal/chunks/{hash}", hash)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(content));

        // 4. Duplicate upload (Deduplication)
        mockMvc.perform(withAuth(post("/internal/chunks/{hash}", hash))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(content))
                .andExpect(status().isOk());

        // 5. Delete chunk
        mockMvc.perform(withAuth(delete("/internal/chunks/{hash}", hash)))
                .andExpect(status().isNoContent());

        // 6. Verify deleted
        mockMvc.perform(withAuth(get("/internal/chunks/{hash}/exists", hash)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false));
    }



    @Test
    @DisplayName("POST /internal/chunks/{hash} rejects corrupted/mismatched hash")
    void testHashMismatch() throws Exception {
        byte[] content = "original content".getBytes(StandardCharsets.UTF_8);
        String fakeHash = "0000000000000000000000000000000000000000000000000000000000000000";

        mockMvc.perform(withAuth(post("/internal/chunks/{hash}", fakeHash))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(content))
                .andExpect(status().isBadRequest());
    }
}

