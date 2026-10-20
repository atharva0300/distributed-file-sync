package com.filesync.storage.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LocalFileStorageService Tests — Content-Addressable Storage")
class LocalFileStorageServiceTest {

    @TempDir
    Path tempDir;

    private LocalFileStorageService storageService;

    // Pre-computed SHA-256 hashes (valid 64-char hex strings)
    private static final String HASH_A = "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb"; // sha256("a")
    private static final String HASH_B = "3e23e8160039594a33894f6564e1b1348bbd7a0088d42c4acb73eeaed59c009d"; // sha256("b")
    private static final String HASH_X = "2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881"; // sha256("foo")

    @BeforeEach
    void setUp() {
        storageService = new LocalFileStorageService(tempDir.toString());
    }

    @Test
    @DisplayName("storeChunk writes data and returns relative path with 2-char prefix")
    void testStoreChunk() {
        byte[] data = "a".getBytes(StandardCharsets.UTF_8);
        String path = storageService.storeChunk(HASH_A, new java.io.ByteArrayInputStream(data), data.length);

        assertNotNull(path);
        assertTrue(path.startsWith("chunks/ca/"), "Path should start with chunks/ca/ prefix: " + path);
        assertTrue(storageService.chunkExists(HASH_A));
    }

    @Test
    @DisplayName("chunkExists returns false for non-existent chunk")
    void testChunkExistsFalse() {
        assertFalse(storageService.chunkExists(HASH_X));
    }

    @Test
    @DisplayName("readChunk returns exact bytes that were stored")
    void testReadChunk() throws IOException {
        byte[] data = "b".getBytes(StandardCharsets.UTF_8);
        storageService.storeChunk(HASH_B, new java.io.ByteArrayInputStream(data), data.length);

        try (InputStream in = storageService.readChunk(HASH_B)) {
            byte[] read = in.readAllBytes();
            assertArrayEquals(data, read);
        }
    }

    @Test
    @DisplayName("deleteChunk removes the physical file")
    void testDeleteChunk() {
        byte[] data = "a".getBytes(StandardCharsets.UTF_8);
        storageService.storeChunk(HASH_A, new java.io.ByteArrayInputStream(data), data.length);
        assertTrue(storageService.chunkExists(HASH_A));

        storageService.deleteChunk(HASH_A);
        assertFalse(storageService.chunkExists(HASH_A));
    }

    @Test
    @DisplayName("Deduplication: storing same chunk twice returns same path without error")
    void testDeduplication() {
        byte[] data = "a".getBytes(StandardCharsets.UTF_8);
        String path1 = storageService.storeChunk(HASH_A, new java.io.ByteArrayInputStream(data), data.length);
        String path2 = storageService.storeChunk(HASH_A, new java.io.ByteArrayInputStream(data), data.length);

        assertEquals(path1, path2);
    }
}

