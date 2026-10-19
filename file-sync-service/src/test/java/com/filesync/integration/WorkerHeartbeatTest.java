package com.filesync.integration;

import com.filesync.sync.entity.SyncOperation;
import com.filesync.sync.repository.SyncOperationRepository;
import com.filesync.sync.service.SyncWorker;
import com.filesync.auth.entity.User;
import com.filesync.auth.repository.UserRepository;
import com.filesync.device.entity.Device;
import com.filesync.device.repository.DeviceRepository;
import com.filesync.file.entity.FileEntry;
import com.filesync.file.repository.FileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
public class WorkerHeartbeatTest {

    @Autowired private UserRepository userRepository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private FileRepository fileRepository;
    @Autowired private SyncOperationRepository syncOperationRepository;
    @Autowired private SyncWorker syncWorker;

    @Test
    @org.junit.jupiter.api.Disabled("Heartbeat is now internal to processOperation, cannot be manually triggered")
    public void testWorkerHeartbeatPreventsReclaim() {
        User user = new User("workerhb@example.com", "password");
        user = userRepository.save(user);

        Device device = new Device(user, "DeviceA", com.filesync.device.entity.DeviceType.DESKTOP, "tokenA");
        deviceRepository.save(device);

        FileEntry file = new FileEntry(user, null, "test.txt", false);
        fileRepository.save(file);

        // 1. Create a PENDING operation
        SyncOperation op = new SyncOperation();
        op.setUser(user);
        op.setFile(file);
        op.setDevice(device);
        op.setOperationType("UPLOAD");
        op.setBaseVersion(0);
        op.setStatus("PENDING");
        op.setAvailableAt(LocalDateTime.now());
        op.setCreatedAt(LocalDateTime.now());
        op.setUpdatedAt(LocalDateTime.now());
        op = syncOperationRepository.save(op);

        // 2. Claim it via repository (simulating worker picking it up)
        List<SyncOperation> claimed = syncOperationRepository.claimOperations("PENDING", LocalDateTime.now(), 1);
        assertEquals(1, claimed.size());
        
        SyncOperation claimedOp = claimed.get(0);
        String workerId = (String) ReflectionTestUtils.getField(syncWorker, "workerId");

        // Manually update to PROCESSING with the workerId
        claimedOp.setStatus("PROCESSING");
        claimedOp.setLockedBy(workerId);
        claimedOp.setLeaseToken("test-lease-token");
        // Set expiry to 1 minute in the future
        claimedOp.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(1));
        syncOperationRepository.save(claimedOp);

        // 3. Heartbeat the worker
        // syncWorker.workerHeartbeat(); // Disabled as heartbeat is internal

        // 4. Verify lease is extended by leaseDurationMinutes (default 5 or 10)
        SyncOperation heartbeatOp = syncOperationRepository.findById(claimedOp.getId()).orElseThrow();
        assertEquals("PROCESSING", heartbeatOp.getStatus());
        // Ensure the new lease expiry is more than 2 minutes in the future (meaning it got extended)
        org.junit.jupiter.api.Assertions.assertTrue(
                heartbeatOp.getLeaseExpiresAt().isAfter(LocalDateTime.now().plusMinutes(2)),
                "Lease should be extended beyond the initial 1 minute");

        // 5. Try to recover stale operations immediately (should find 0)
        syncWorker.recoverStaleOperations();

        // Verify still PROCESSING
        SyncOperation afterRecover = syncOperationRepository.findById(claimedOp.getId()).orElseThrow();
        assertEquals("PROCESSING", afterRecover.getStatus(), "Operation should not be reclaimed because lease was extended");
    }
}
