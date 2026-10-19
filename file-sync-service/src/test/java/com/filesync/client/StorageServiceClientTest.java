package com.filesync.client;

import com.filesync.client.dto.ChunkExistsResponse;
import com.filesync.client.dto.ChunkStorageResponse;
import com.filesync.common.exception.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@DisplayName("StorageServiceClient Tests — Inter-Service HTTP Communication")
class StorageServiceClientTest {

    private StorageServiceClient client;
    private MockRestServiceServer mockServer;

    private static final String BASE_URL = "http://localhost:8081";
    private static final String HASH = "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb";

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        this.mockServer = MockRestServiceServer.bindTo(builder).build();

        // Instantiate client using builder reflecting the mock
        RestClient restClient = builder.build();
        this.client = new StorageServiceClient(BASE_URL, "test-api-key");
        // Let's use reflection to inject the mocked RestClient
        try {
            var field = StorageServiceClient.class.getDeclaredField("restClient");
            field.setAccessible(true);
            field.set(client, restClient);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("storeChunk sends POST to /internal/chunks/{hash} and returns response")
    void testStoreChunkSuccess() {
        byte[] payload = "chunk-data".getBytes(StandardCharsets.UTF_8);

        mockServer.expect(requestTo(BASE_URL + "/internal/chunks/" + HASH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andRespond(withSuccess(
                        """
                        {
                            "hash": "%s",
                            "storageKey": "chunks/ca/%s",
                            "alreadyExisted": false,
                            "size": 10
                        }
                        """.formatted(HASH, HASH),
                        MediaType.APPLICATION_JSON));

        ChunkStorageResponse response = client.storeChunk(HASH, payload);

        assertNotNull(response);
        assertEquals(HASH, response.hash());
        assertEquals("chunks/ca/" + HASH, response.storageKey());
        assertFalse(response.alreadyExisted());
        assertEquals(10, response.size());
        mockServer.verify();
    }

    @Test
    @DisplayName("chunkExists queries GET /internal/chunks/{hash}/exists")
    void testChunkExists() {
        mockServer.expect(requestTo(BASE_URL + "/internal/chunks/" + HASH + "/exists"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {"hash": "%s", "exists": true}
                        """.formatted(HASH),
                        MediaType.APPLICATION_JSON));

        boolean exists = client.chunkExists(HASH);

        assertTrue(exists);
        mockServer.verify();
    }

    @Test
    @DisplayName("readChunk streams bytes from GET /internal/chunks/{hash}")
    void testReadChunk() throws Exception {
        byte[] payload = "chunk-bytes-12345".getBytes(StandardCharsets.UTF_8);

        mockServer.expect(requestTo(BASE_URL + "/internal/chunks/" + HASH))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(payload, MediaType.APPLICATION_OCTET_STREAM));

        try (InputStream in = client.readChunk(HASH)) {
            byte[] read = in.readAllBytes();
            assertArrayEquals(payload, read);
        }
        mockServer.verify();
    }

    @Test
    @DisplayName("deleteChunk sends DELETE to /internal/chunks/{hash}")
    void testDeleteChunk() {
        mockServer.expect(requestTo(BASE_URL + "/internal/chunks/" + HASH))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withNoContent());

        assertDoesNotThrow(() -> client.deleteChunk(HASH));
        mockServer.verify();
    }

    @Test
    @DisplayName("Storage service failure throws StorageException gracefully")
    void testStorageServiceFailure() {
        byte[] payload = "chunk-data".getBytes(StandardCharsets.UTF_8);

        mockServer.expect(requestTo(BASE_URL + "/internal/chunks/" + HASH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThrows(StorageException.class, () -> client.storeChunk(HASH, payload));
        mockServer.verify();
    }
}
