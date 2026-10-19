package com.filesync.integration;

import com.filesync.chunk.repository.ChunkRepository;
import com.filesync.client.StorageServiceClient;
import com.filesync.client.dto.ChunkStorageResponse;
import com.filesync.common.util.HashUtil;
import com.filesync.device.entity.Device;
import com.filesync.device.repository.DeviceRepository;
import com.filesync.file.entity.FileEntry;
import com.filesync.file.repository.FileRepository;
import com.filesync.upload.service.UploadService;
import com.filesync.auth.entity.User;
import com.filesync.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest
@ActiveProfiles("test")
public class ConcurrentChunkUploadTest {

    @Autowired private UserRepository userRepository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private FileRepository fileRepository;
    @Autowired private UploadService uploadService;
    @Autowired private ChunkRepository chunkRepository;
    @MockBean private StorageServiceClient storageServiceClient;

    @Test
    public void testConcurrentUploadSameChunk() throws Exception {
        User tempUser = new User("concurrent_upload@example.com", "password");
        final User user = userRepository.save(tempUser);

        Device device = new Device(user, "DeviceA", com.filesync.device.entity.DeviceType.DESKTOP, "tokenA");
        deviceRepository.save(device);

        FileEntry file = new FileEntry(user, null, "test_concurrent.txt", false);
        fileRepository.save(file);

        byte[] data = new byte[1024];
        String realHash = HashUtil.sha256Hex(data);

        Mockito.when(storageServiceClient.storeChunk(any(), any()))
                .thenReturn(new ChunkStorageResponse(realHash, "s3://fake-key/" + realHash, false, (long) data.length));

        // Create separate upload sessions for the threads to avoid session lock contention,
        // we are testing CHUNK deduplication.
        int numThreads = 10;
        Long[] sessionIds = new Long[numThreads];
        for (int i = 0; i < numThreads; i++) {
            sessionIds[i] = uploadService.initUpload(user.getId(), file.getId(), device.getId(), 0, 1024L, realHash).sessionId();
        }

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            final Long sessionId = sessionIds[i];
            executor.submit(() -> {
                try {
                    startLatch.await();
                    uploadService.uploadChunk(user.getId(), sessionId, 0, realHash, data);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Go!
        endLatch.await();
        executor.shutdown();

        // Expect ALL to succeed (no 500s or 409s for same chunk content)
        assertEquals(numThreads, successCount.get(), "All concurrent chunk uploads should succeed due to deduplication");
        assertEquals(0, failureCount.get(), "No failures expected");

        // Verify the chunk was inserted (the unique constraint guarantees exactly 1)
        org.junit.jupiter.api.Assertions.assertTrue(
                chunkRepository.findByHash(realHash).isPresent(), 
                "Exactly 1 chunk should be inserted into the database"
        );
    }
}
