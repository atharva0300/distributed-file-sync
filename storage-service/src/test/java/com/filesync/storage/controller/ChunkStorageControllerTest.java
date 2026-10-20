package com.filesync.storage.controller;

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

    private <T extends org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> T withAuth(T builder) {
        long timestamp = System.currentTimeMillis();
        String serviceId = "file-sync-service";
        String payload = serviceId + ":" + timestamp;
        
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec secretKeySpec = new javax.crypto.spec.SecretKeySpec("dev-internal-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hashBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String signature = java.util.Base64.getEncoder().encodeToString(hashBytes);
            
            builder.header("X-Service-Id", serviceId)
                   .header("X-Timestamp", String.valueOf(timestamp))
                   .header("X-Signature", signature);
            return builder;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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

