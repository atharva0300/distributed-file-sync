package com.filesync.client;

import com.filesync.client.dto.ChunkExistsResponse;
import com.filesync.client.dto.ChunkStorageResponse;
import com.filesync.common.exception.StorageException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

@Slf4j
@Component
public class StorageServiceClient {

    private final RestClient restClient;
    private final String baseUrl;

    public StorageServiceClient(
            @Value("${app.storage-service.url:http://localhost:8081}") String storageServiceUrl,
            @Value("${app.internal.api-key:dev-internal-key}") String apiKey) {
        this.baseUrl = storageServiceUrl;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(15));

        this.restClient = RestClient.builder()
                .baseUrl(storageServiceUrl)
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    long timestamp = System.currentTimeMillis();
                    String serviceId = "file-sync-service";
                    String payload = serviceId + ":" + timestamp;
                    String signature = computeHmacSha256(payload, apiKey);
                    
                    request.getHeaders().add("X-Service-Id", serviceId);
                    request.getHeaders().add("X-Timestamp", String.valueOf(timestamp));
                    request.getHeaders().add("X-Signature", signature);
                    return execution.execute(request, body);
                })
                .build();
        log.info("Initialized StorageServiceClient targeting: {} with HMAC auth", storageServiceUrl);
    }

    private String computeHmacSha256(String data, String key) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec secretKeySpec = new javax.crypto.spec.SecretKeySpec(key.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC-SHA256", e);
        }
    }

    public ChunkStorageResponse storeChunk(String hash, InputStream stream, long size) {
        try {
            return restClient.post()
                    .uri("/internal/chunks/{hash}", hash)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(size)
                    .body(new org.springframework.core.io.InputStreamResource(stream))
                    .retrieve()
                    .body(ChunkStorageResponse.class);
        } catch (Exception e) {
            log.error("Failed to store streaming chunk {} in storage-service: {}", hash, e.getMessage());
            throw new StorageException("Storage Service error storing streaming chunk: " + hash, e);
        }
    }

    public ChunkStorageResponse storeChunk(String hash, byte[] data) {
        try {
            return restClient.post()
                    .uri("/internal/chunks/{hash}", hash)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(data)
                    .retrieve()
                    .body(ChunkStorageResponse.class);
        } catch (Exception e) {
            log.error("Failed to store chunk {} in storage-service: {}", hash, e.getMessage());
            throw new StorageException("Storage Service error storing chunk: " + hash, e);
        }
    }

    public boolean chunkExists(String hash) {
        try {
            ChunkExistsResponse res = restClient.get()
                    .uri("/internal/chunks/{hash}/exists", hash)
                    .retrieve()
                    .body(ChunkExistsResponse.class);
            return res != null && res.exists();
        } catch (Exception e) {
            log.error("Failed to check chunk {} existence in storage-service: {}", hash, e.getMessage());
            throw new StorageException("Storage Service error checking chunk: " + hash, e);
        }
    }

    public InputStream readChunk(String hash) {
        try {
            Resource resource = restClient.get()
                    .uri("/internal/chunks/{hash}", hash)
                    .accept(MediaType.APPLICATION_OCTET_STREAM)
                    .retrieve()
                    .body(Resource.class);
            if (resource == null) {
                throw new StorageException("Chunk not found in storage-service: " + hash);
            }
            return resource.getInputStream();
        } catch (IOException e) {
            throw new StorageException("Failed to read stream for chunk: " + hash, e);
        } catch (Exception e) {
            log.error("Failed to read chunk {} from storage-service: {}", hash, e.getMessage());
            throw new StorageException("Storage Service error reading chunk: " + hash, e);
        }
    }

    public void deleteChunk(String hash) {
        try {
            restClient.delete()
                    .uri("/internal/chunks/{hash}", hash)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("Failed to delete chunk {} from storage-service: {}", hash, e.getMessage());
            throw new StorageException("Storage Service error deleting chunk: " + hash, e);
        }
    }
}
