package com.filesync.integration;

import com.filesync.common.exception.ConflictException;
import com.filesync.sync.dto.QueueSyncOperationRequest;
import com.filesync.sync.service.SyncService;
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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
public class PayloadMismatchTest {

    @Autowired private UserRepository userRepository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private FileRepository fileRepository;
    @Autowired private SyncService syncService;

    @Test
    public void testIdempotentQueueWithSamePayload() {
        User user = new User("payload1@example.com", "password");
        user = userRepository.save(user);

        Device device = new Device(user, "DeviceA", com.filesync.device.entity.DeviceType.DESKTOP, "tokenA");
        device = deviceRepository.save(device);

        FileEntry file = new FileEntry(user, null, "test.txt", false);
        file = fileRepository.save(file);

        QueueSyncOperationRequest req1 = new QueueSyncOperationRequest(
                device.getId(), file.getId(), "RENAME", null, "{\"newName\":\"test2.txt\"}", "client-op-123", null
        );

        // First queue succeeds
        var resp1 = syncService.queueOperation(user.getId(), req1);

        // Second queue with same payload returns original operation (idempotent)
        var resp2 = syncService.queueOperation(user.getId(), req1);
        assertEquals(resp1.id(), resp2.id());
    }

    @Test
    public void testConflictQueueWithDifferentPayload() {
        User tempUser = new User("payload2@example.com", "password");
        final User user = userRepository.save(tempUser);

        Device device = new Device(user, "DeviceA", com.filesync.device.entity.DeviceType.DESKTOP, "tokenA");
        device = deviceRepository.save(device);

        FileEntry file = new FileEntry(user, null, "test.txt", false);
        file = fileRepository.save(file);

        QueueSyncOperationRequest req1 = new QueueSyncOperationRequest(
                device.getId(), file.getId(), "RENAME", null, "{\"newName\":\"test2.txt\"}", "client-op-999", null
        );

        syncService.queueOperation(user.getId(), req1);

        // Second queue with DIFFERENT payload for same clientOperationId
        QueueSyncOperationRequest req2 = new QueueSyncOperationRequest(
                device.getId(), file.getId(), "RENAME", null, "{\"newName\":\"test3.txt\"}", "client-op-999", null
        );

        // Should throw ConflictException (mapped to 409)
        assertThrows(ConflictException.class, () -> {
            syncService.queueOperation(user.getId(), req2);
        });
    }
}
