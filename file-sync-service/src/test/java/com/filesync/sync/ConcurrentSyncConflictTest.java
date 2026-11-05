package com.filesync.sync;

import static com.filesync.testsupport.UploadTestSupport.uploadSingleChunk;

import com.filesync.auth.dto.RegisterRequest;
import com.filesync.auth.service.AuthService;
import com.filesync.device.dto.CreateDeviceRequest;
import com.filesync.device.entity.DeviceStatus;
import com.filesync.device.entity.DeviceType;
import com.filesync.device.service.DeviceService;
import com.filesync.file.dto.CreateFolderRequest;
import com.filesync.file.entity.FileEntry;
import com.filesync.file.repository.FileRepository;
import com.filesync.file.service.FileService;
import com.filesync.sync.dto.QueueSyncOperationRequest;
import com.filesync.sync.entity.SyncOperation;
import com.filesync.sync.repository.SyncOperationRepository;
import com.filesync.sync.service.SyncService;
import com.filesync.sync.service.SyncWorker;
import com.filesync.upload.service.UploadService;
import com.filesync.common.util.HashUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the core conflict scenario:
 *
 *   1. Two devices download file at v1
 *   2. Both go OFFLINE
 *   3. Device A makes changes -> queues UPLOAD with baseVersion=1
 *   4. Device B makes changes -> queues UPLOAD with baseVersion=1
 *   5. Device A reconnects -> background worker processes -> v2 created -> no conflict
 *   6. Device B reconnects -> background worker processes -> baseVersion=1 != server v2 -> CONFLICT!
 */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrentSyncConflictTest {

    @Autowired AuthService authService;
    @Autowired DeviceService deviceService;
    @Autowired FileService fileService;
    @Autowired UploadService uploadService;
    @Autowired SyncService syncService;
    @Autowired SyncWorker syncWorker;
    @Autowired SyncOperationRepository operationRepository;
    @Autowired FileRepository fileRepository;
    @org.springframework.boot.test.mock.mockito.MockBean
    private com.filesync.client.StorageServiceClient storageClient;

    private Long userId;
    private Long laptopId;
    private Long desktopId;
    private Long fileId;

    @BeforeEach
    void setUp() {
        // Register user
        var auth = authService.register(new RegisterRequest(
                "concurrency-test-" + System.nanoTime() + "@test.com", "password123"));
        userId = auth.userId();

        // Register devices
        var laptop = deviceService.registerDevice(userId,
                new CreateDeviceRequest("Laptop", DeviceType.LAPTOP));
        laptopId = laptop.id();

        var desktop = deviceService.registerDevice(userId,
                new CreateDeviceRequest("Desktop", DeviceType.DESKTOP));
        desktopId = desktop.id();

        // Track actual chunk data by hash so readChunk returns content-matched data
        java.util.Map<String, byte[]> storedChunks = new java.util.concurrent.ConcurrentHashMap<>();

        org.mockito.Mockito.when(storageClient.storeChunk(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    String hash = invocation.getArgument(0);
                    byte[] data = invocation.getArgument(1);
                    storedChunks.put(hash, data);
                    return new com.filesync.client.dto.ChunkStorageResponse(hash, "chunks/" + hash.substring(0, 2) + "/" + hash, false, (long) data.length);
                });

        org.mockito.Mockito.when(storageClient.readChunk(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    String hash = invocation.getArgument(0);
                    byte[] data = storedChunks.getOrDefault(hash, new byte[0]);
                    System.out.println("DEBUG: readChunk requested hash=" + hash + ", found data length=" + data.length);
                    return new java.io.ByteArrayInputStream(data);
                });

        org.mockito.Mockito.doNothing().when(storageClient).deleteChunk(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void concurrentModification_secondDeviceGetsConflict() throws Exception {
        // 1. Create file record and upload initial version from Laptop (creates v1)
        var user = deviceService.getDeviceAndVerifyOwnership(laptopId, userId).getUser();
        FileEntry newFile = new FileEntry(user, null, "report.txt", false);
        newFile = fileRepository.save(newFile);
        fileId = newFile.getId();

        byte[] chunkData = "original content version 1".getBytes();
        String chunkHash = HashUtil.sha256Hex(chunkData);

        Long laptopSessionId = uploadService.initUpload(userId, fileId, laptopId, null, (long) chunkData.length, chunkHash).sessionId();
        uploadSingleChunk(uploadService, userId, fileId, laptopSessionId, null, chunkHash, chunkData);
        uploadService.completeUpload(userId, laptopSessionId, null);

        // Verify initial state: v1
        FileEntry file = fileRepository.findByIdAndUserIdAndDeletedFalse(fileId, userId).orElseThrow();
        assertThat(file.getCurrentVersion()).isEqualTo(1);

        // 2. Desktop also downloads the file and goes offline at v1
        // Desktop creates its own session based on v1 (base_version = 1) with modified content
        byte[] desktopEditData = "desktop modified content".getBytes();
        String desktopHash = HashUtil.sha256Hex(desktopEditData);

        // Desktop creates an upload session (at this point, server is still at v1)
        Long desktopSessionId = uploadService.initUpload(userId, fileId, desktopId, 1,
                (long) desktopEditData.length, desktopHash).sessionId();
        uploadSingleChunk(uploadService, userId, fileId, desktopSessionId, 1, desktopHash, desktopEditData);
        // Desktop goes offline — does NOT complete the session yet

        // 3. Laptop reconnects first and advances file to v2 (server is now at v2)
        // Simulate by directly updating the DB (Laptop completes its own edit)
        file = fileRepository.findByIdAndUserIdAndDeletedFalse(fileId, userId).orElseThrow();
        file.setCurrentVersion(2);
        fileRepository.save(file);

        // 4. Desktop reconnects — queues an UPLOAD operation with its own session (baseVersion=1)
        // Server is now at v2, so this will be a CONFLICT
        var desktopOp = syncService.queueOperation(userId, new QueueSyncOperationRequest(
                desktopId, fileId, "UPLOAD", 1, desktopHash, "desktop-conflicting-edit", desktopSessionId));

        // 5. Background worker runs - desktop op has baseVersion=1, server is at v2 → CONFLICT!
        syncWorker.processPendingOperations();

        // Find desktop op status
        var desktopLatestOp = operationRepository.findById(desktopOp.id()).orElseThrow();
        assertThat(desktopLatestOp.getStatus())
                .as("Desktop operation should be in CONFLICT because server advanced to v2 while desktop was at v1")
                .isEqualTo("CONFLICT");
    }
}
