package com.filesync.storage.service;

import com.filesync.storage.exception.ChunkHashMismatchException;
import com.filesync.storage.exception.StorageException;
import com.filesync.storage.util.HashUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LocalFileStorageService — upload integrity (B4 regressions)")
class LocalFileStorageIntegrityTest {

    @TempDir Path tempDir;
    LocalFileStorageService storage;

    static final byte[] GOOD = "good chunk bytes".getBytes(StandardCharsets.UTF_8);
    static final byte[] BAD = "tampered bytes!!".getBytes(StandardCharsets.UTF_8);
    static final String HASH = HashUtil.sha256Hex(GOOD);

    @BeforeEach
    void setUp() {
        storage = new LocalFileStorageService(tempDir.toString());
    }

    private long tmpFileCount() throws IOException {
        try (Stream<Path> s = Files.walk(tempDir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".tmp")).count();
        }
    }

    private byte[] read(String hash) throws IOException {
        try (var in = storage.readChunk(hash)) {
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("existing valid chunk survives an invalid upload under the same hash")
    void existingChunkSurvivesInvalidUpload() throws IOException {
        storage.storeChunk(HASH, new ByteArrayInputStream(GOOD), GOOD.length);

        assertThrows(ChunkHashMismatchException.class,
                () -> storage.storeChunk(HASH, new ByteArrayInputStream(BAD), BAD.length));

        assertTrue(storage.chunkExists(HASH));
        assertArrayEquals(GOOD, read(HASH));
        assertEquals(0, tmpFileCount());
    }

    @Test
    @DisplayName("invalid upload of a new chunk stores nothing and leaves no temp file")
    void newChunkInvalidUploadStoresNothing() throws IOException {
        assertThrows(ChunkHashMismatchException.class,
                () -> storage.storeChunk(HASH, new ByteArrayInputStream(BAD), BAD.length));

        assertFalse(storage.chunkExists(HASH));
        assertEquals(0, tmpFileCount());
    }

    @Test
    @DisplayName("body shorter than declared Content-Length is rejected")
    void truncatedBodyRejected() throws IOException {
        assertThrows(ChunkHashMismatchException.class,
                () -> storage.storeChunk(HASH, new ByteArrayInputStream(GOOD), GOOD.length + 5));
        assertFalse(storage.chunkExists(HASH));
        assertEquals(0, tmpFileCount());
    }

    @Test
    @DisplayName("concurrent uploads of the same chunk all succeed and store exactly one intact object")
    void concurrentUploadsSameChunk() throws Exception {
        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return storage.storeChunk(HASH, new ByteArrayInputStream(GOOD), GOOD.length);
            }));
        }
        start.countDown();
        for (Future<String> f : results) {
            assertNotNull(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertArrayEquals(GOOD, read(HASH));
        assertEquals(0, tmpFileCount());
    }

    @Test
    @DisplayName("concurrent valid and invalid uploads never corrupt the stored chunk")
    void concurrentValidAndInvalid() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final boolean good = i % 2 == 0;
            fs.add(pool.submit(() -> {
                start.await();
                try {
                    byte[] data = good ? GOOD : BAD;
                    storage.storeChunk(HASH, new ByteArrayInputStream(data), data.length);
                } catch (ChunkHashMismatchException expected) {
                    assertFalse(good);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : fs) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertArrayEquals(GOOD, read(HASH));
        assertEquals(0, tmpFileCount());
    }

    @Test
    @DisplayName("failure while promoting the verified temp file is reported and the temp file is cleaned up")
    void promotionFailureCleansUp() throws IOException {
        // A non-empty directory squatting on the final name makes the atomic rename fail.
        Path finalPath = tempDir.resolve("chunks").resolve(HASH.substring(0, 2)).resolve(HASH);
        Files.createDirectories(finalPath);
        Files.writeString(finalPath.resolve("blocker"), "x");

        assertThrows(StorageException.class,
                () -> storage.storeChunk(HASH, new ByteArrayInputStream(GOOD), GOOD.length));

        assertEquals(0, tmpFileCount());
        assertFalse(storage.chunkExists(HASH), "a directory is not a stored chunk");
    }
}
