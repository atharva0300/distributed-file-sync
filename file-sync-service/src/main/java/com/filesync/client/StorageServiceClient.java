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
            @Value("${app.internal.api-key}") String apiKey) {
        this.baseUrl = storageServiceUrl;
        InternalRequestSigner signer = new InternalRequestSigner(apiKey);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(15));

        this.restClient = RestClient.builder()
                .baseUrl(storageServiceUrl)
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    var signed = signer.sign(request.getMethod().name(),
                            request.getURI().getRawPath(), request.getURI().getRawQuery(), body);
                    request.getHeaders().set("X-Service-Id", signed.serviceId());
                    request.getHeaders().set("X-Timestamp", String.valueOf(signed.timestamp()));
                    request.getHeaders().set("X-Nonce", signed.nonce());
                    request.getHeaders().set("X-Content-SHA256", signed.bodySha256());
                    request.getHeaders().set("X-Signature", signed.signature());
                    return execution.execute(request, body);
                })
                .build();
        log.info("Initialized StorageServiceClient targeting: {} with HMAC auth", storageServiceUrl);
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
