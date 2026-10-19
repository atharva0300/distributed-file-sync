package com.filesync.sync;

import com.filesync.FileSyncApplication;
import com.filesync.auth.entity.User;
import com.filesync.auth.repository.UserRepository;
import com.filesync.chunk.entity.Chunk;
import com.filesync.chunk.entity.ChunkStatus;
import com.filesync.chunk.repository.ChunkRepository;
import com.filesync.chunk.service.OrphanChunkCleanupService;
import com.filesync.client.dto.ChunkStorageResponse;
import com.filesync.device.entity.Device;
import com.filesync.device.repository.DeviceRepository;
import com.filesync.file.entity.FileEntry;
import com.filesync.file.repository.FileRepository;
import com.filesync.upload.service.UploadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(classes = FileSyncApplication.class)
@ActiveProfiles("test")
public class GarbageCollectionRaceTest {

    @Autowired private UploadService uploadService;
    @Autowired private OrphanChunkCleanupService gcService;
    @Autowired private UserRepository userRepository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private FileRepository fileRepository;
    @Autowired private ChunkRepository chunkRepository;

    @MockBean
    private com.filesync.client.StorageServiceClient storageServiceClient;

    @Test
    public void testGcVsUploadCompletionRace() throws Exception {
        User user = userRepository.save(new User("gc_race@example.com", "password"));
        Device device = deviceRepository.save(new Device(user, "DeviceA", com.filesync.device.entity.DeviceType.DESKTOP, "tokenA"));
        FileEntry file = fileRepository.save(new FileEntry(user, null, "test_gc.txt", false));

        byte[] data = new byte[1024];
        String realHash = com.filesync.common.util.HashUtil.sha256Hex(data);

        org.mockito.Mockito.when(storageServiceClient.storeChunk(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ChunkStorageResponse(realHash, "s3://fake-key/" + realHash, false, (long) data.length));
        
        org.mockito.Mockito.when(storageServiceClient.readChunk(realHash))
                .thenAnswer(inv -> new java.io.ByteArrayInputStream(data));

        final Long sessionId = uploadService.initUpload(user.getId(), file.getId(), device.getId(), 0, 1024L, realHash).sessionId();
        uploadService.uploadChunk(user.getId(), sessionId, 0, realHash, data);

        // At this point, the chunk is ACTIVE and unreferenced by any FileVersion, making it a candidate for GC!
        // We will run GC and CompleteUpload concurrently.
        
        // Wait! We need to make the chunk look old enough to be GC'd.
        Chunk chunk = chunkRepository.findByHash(realHash).orElseThrow();
        chunk.setUpdatedAt(java.time.LocalDateTime.now().minusHours(24));
        chunkRepository.save(chunk);

        int numThreads = 2;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        AtomicInteger gcCount = new AtomicInteger(0);
        AtomicInteger completeCount = new AtomicInteger(0);

        executor.submit(() -> {
            try {
                startLatch.await();
                gcService.cleanupOrphanChunks();
                gcCount.incrementAndGet();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                endLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                uploadService.completeUpload(user.getId(), sessionId, null);
                completeCount.incrementAndGet();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                endLatch.countDown();
            }
        });

        startLatch.countDown();
        endLatch.await();
        executor.shutdown();

        // The chunk should NOT be deleted, because either:
        // 1. completeUpload finished first, so GC saw the reference and skipped it.
        // 2. GC locked the row, saw 0 references, marked it DELETING, but then completeUpload...
        // Wait, completeUpload does not lock the chunk row until it creates the FileVersionChunk, which doesn't actually lock it, it just references it.
        // But if GC deleted it, the chunk won't be found. We expect the chunk to still exist and be ACTIVE.
        
        Chunk finalChunk = chunkRepository.findByHash(realHash).orElse(null);
        org.junit.jupiter.api.Assertions.assertNotNull(finalChunk, "Chunk should not be deleted");
        assertEquals(ChunkStatus.ACTIVE, finalChunk.getStatus(), "Chunk should be ACTIVE");
    }
}
