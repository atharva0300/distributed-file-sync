package com.filesync.sync;

import com.filesync.FileSyncApplication;
import com.filesync.auth.entity.User;
import com.filesync.auth.repository.UserRepository;
import com.filesync.device.entity.Device;
import com.filesync.device.entity.DeviceType;
import com.filesync.device.repository.DeviceRepository;
import com.filesync.file.entity.FileEntry;
import com.filesync.file.repository.FileRepository;
import com.filesync.file.service.FileService;
import com.filesync.sync.entity.SyncOperation;
import com.filesync.sync.repository.SyncOperationRepository;
import com.filesync.sync.service.SyncWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/** Worker concurrency is bounded and long-running operations do not block the maintenance scheduler. */
@SpringBootTest(classes = FileSyncApplication.class)
@ActiveProfiles("test")
class SyncWorkerSchedulingTest {

    @Autowired SyncWorker syncWorker;
    @Autowired SyncOperationRepository operationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DeviceRepository deviceRepository;
    @Autowired FileRepository fileRepository;
    @Autowired ThreadPoolTaskScheduler taskScheduler;
    @MockBean FileService fileService;
    @MockBean com.filesync.client.StorageServiceClient storage;

    @Test
    void workerConcurrencyIsBoundedAndSchedulerStaysFree() throws Exception {
        User user = userRepository.save(new User("sched-" + UUID.randomUUID() + "@example.com", "pw"));
        Device device = deviceRepository.save(new Device(user, "d", DeviceType.DESKTOP, UUID.randomUUID().toString()));

        int total = 5; // more than the worker pool (2 in tests/default)
        Set<Long> ourFiles = ConcurrentHashMap.newKeySet();
        List<Long> opIds = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            FileEntry f = fileRepository.save(new FileEntry(user, null, "s-" + UUID.randomUUID(), false));
            ourFiles.add(f.getId());
            SyncOperation op = new SyncOperation();
            op.setUser(user);
            op.setFile(f);
            op.setDevice(device);
            op.setOperationType("RENAME");
            op.setStatus("PENDING");
            op.setPayload("{\"newName\":\"n-" + UUID.randomUUID() + "\"}");
            op.setAvailableAt(LocalDateTime.now());
            op.setCreatedAt(LocalDateTime.now());
            op.setUpdatedAt(LocalDateTime.now());
            opIds.add(operationRepository.save(op).getId());
        }

        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        when(fileService.renameFileAtomically(anyLong(), anyLong(), any(), any(), any())).thenAnswer(inv -> {
            if (!ourFiles.contains(inv.<Long>getArgument(1))) {
                return null;
            }
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                assertTrue(release.await(30, TimeUnit.SECONDS));
            } finally {
                inFlight.decrementAndGet();
                finished.incrementAndGet();
            }
            return null;
        });

        long t0 = System.nanoTime();
        syncWorker.dispatchPendingOperations();
        syncWorker.dispatchPendingOperations(); // second call must not exceed the bound
        long dispatchMillis = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(dispatchMillis < 3000, "dispatch must hand work off, not run it (took " + dispatchMillis + "ms)");

        // Maintenance scheduler is not blocked by the running operations.
        CountDownLatch maintenanceRan = new CountDownLatch(1);
        taskScheduler.execute(maintenanceRan::countDown);
        assertTrue(maintenanceRan.await(5, TimeUnit.SECONDS), "maintenance work must run while operations are busy");

        Thread.sleep(500);
        assertEquals(2, inFlight.get(), "exactly the worker-pool size runs concurrently");

        release.countDown();
        // Keep dispatching until the remaining operations are picked up.
        long deadline = System.currentTimeMillis() + 30_000;
        while (finished.get() < total && System.currentTimeMillis() < deadline) {
            syncWorker.dispatchPendingOperations();
            Thread.sleep(100);
        }
        assertEquals(total, finished.get());
        assertEquals(2, maxInFlight.get(), "never more than the pool size in flight");

        Thread.sleep(500);
        for (Long id : opIds) {
            assertEquals("COMPLETED", operationRepository.findById(id).orElseThrow().getStatus());
        }
    }
}
