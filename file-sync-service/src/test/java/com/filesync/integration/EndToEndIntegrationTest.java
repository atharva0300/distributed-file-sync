package com.filesync.integration;

import static com.filesync.testsupport.UploadTestSupport.uploadSingleChunk;

import com.filesync.chunk.repository.ChunkRepository;
import com.filesync.chunk.service.OrphanChunkCleanupService;
import com.filesync.conflict.entity.Conflict;
import com.filesync.conflict.repository.ConflictRepository;
import com.filesync.conflict.service.ConflictService;
import com.filesync.device.entity.Device;
import com.filesync.device.repository.DeviceRepository;
import com.filesync.file.entity.FileEntry;
import com.filesync.file.repository.FileRepository;
import com.filesync.sync.entity.SyncOperation;
import com.filesync.sync.repository.SyncOperationRepository;
import com.filesync.sync.service.SyncWorker;
import com.filesync.upload.service.UploadService;
import com.filesync.auth.entity.User;
import com.filesync.auth.repository.UserRepository;
import com.filesync.version.repository.FileVersionRepository;
import com.filesync.common.util.HashUtil;
import com.filesync.client.StorageServiceClient;
import com.filesync.client.dto.ChunkStorageResponse;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.mockito.Mockito;
import static org.mockito.ArgumentMatchers.any;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class EndToEndIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private FileRepository fileRepository;
    @Autowired private FileVersionRepository versionRepository;
    @Autowired private UploadService uploadService;
    @Autowired private SyncOperationRepository syncOperationRepository;
    @Autowired private ConflictRepository conflictRepository;
    @Autowired private ConflictService conflictService;
    @Autowired private ChunkRepository chunkRepository;
    @Autowired private OrphanChunkCleanupService orphanChunkCleanupService;
    @Autowired private SyncWorker syncWorker;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @MockBean private StorageServiceClient storageServiceClient;

    /**
     * End-to-end test that exercises:
     *   1. File upload (DeviceA creates v1)
     *   2. Concurrent conflicting upload by DeviceB (based on v0 → conflict)
     *   3. Conflict creation and resolution (CREATE_CONFLICT_COPY)
     *   4. Orphan chunk GC
     *
     * Correct timeline:
     *   t0: File created (v0)
     *   t1: DeviceA creates session (baseVersion=0), uploads chunk
     *   t2: DeviceB ALSO creates session (baseVersion=0) while file still at v0 → goes offline
     *   t3: DeviceA completes upload → file at v1
     *   t4: DeviceB reconnects, queues UPLOAD op → worker detects conflict (session.baseVersion=0 != file.v1)
     *   t5: User resolves conflict via CREATE_CONFLICT_COPY
     *   t6: GC removes orphan chunk
     */
    @Test
    public void testEndToEndSyncAndConflictAndGC() throws Exception {
        // 1. Setup User and Devices
        User user = new User("test_integration@example.com", "password");
        user = userRepository.save(user);

        Device deviceA = new Device(user, "DeviceA", com.filesync.device.entity.DeviceType.DESKTOP, "tokenA");
        deviceA = deviceRepository.save(deviceA);

        Device deviceB = new Device(user, "DeviceB", com.filesync.device.entity.DeviceType.PHONE, "tokenB");
        deviceB = deviceRepository.save(deviceB);

        // 2. Create the file entry (not yet uploaded — at v0)
        FileEntry file = new FileEntry(user, null, "test_sync.txt", false);
        file = fileRepository.save(file);

        byte[] data = new byte[1024];
        String realHash = HashUtil.sha256Hex(data);

        Mockito.when(storageServiceClient.storeChunk(any(), any()))
                .thenReturn(new ChunkStorageResponse(realHash, "s3://fake-key/" + realHash, false, (long) data.length));
        Mockito.when(storageServiceClient.readChunk(any()))
                .thenAnswer(invocation -> new java.io.ByteArrayInputStream(data));

        // 3. DeviceA creates upload session at v0 (new file)
        Long sessionIdA = uploadService.initUpload(user.getId(), file.getId(), deviceA.getId(), 0, 1024L, realHash).sessionId();
        uploadSingleChunk(uploadService, user.getId(), file.getId(), sessionIdA, 0, realHash, data);

        // 4. DeviceB ALSO creates its own upload session at v0 (same starting point, file still at v0)
        //    DeviceB made its own edits but uses the same content for simplicity
        Long sessionIdB = uploadService.initUpload(user.getId(), file.getId(), deviceB.getId(), 0, 1024L, realHash).sessionId();
        uploadSingleChunk(uploadService, user.getId(), file.getId(), sessionIdB, 0, realHash, data);
        // DeviceB goes offline → does NOT complete session yet

        // 5. DeviceA completes upload → file advances to v1
        uploadService.completeUpload(user.getId(), sessionIdA, null);

        // Verify file is at v1
        FileEntry fileAtV1 = fileRepository.findById(file.getId()).orElseThrow();
        assertEquals(1, fileAtV1.getCurrentVersion());

        // 6. DeviceB reconnects — queues UPLOAD operation with its session (baseVersion=0)
        //    Server is at v1, so this will be a CONFLICT when the worker processes it
        SyncOperation op = new SyncOperation();
        op.setUser(user);
        op.setFile(fileAtV1);
        op.setDevice(deviceB);
        op.setOperationType("UPLOAD");
        op.setBaseVersion(0);
        op.setStatus("PENDING");
        op.setUploadSessionId(sessionIdB);
        op.setAvailableAt(LocalDateTime.now());
        op.setCreatedAt(LocalDateTime.now());
        op.setUpdatedAt(LocalDateTime.now());
        syncOperationRepository.save(op);

        // 7. SyncWorker runs — detects conflict (session.baseVersion=0 != file.currentVersion=1)
        syncWorker.processPendingOperations();

        // 8. Verify Conflict was created
        List<Conflict> conflicts = conflictRepository.findByUserIdAndStatus(user.getId(), "OPEN");
        assertEquals(1, conflicts.size(), "Expected exactly one OPEN conflict");
        Conflict conflict = conflicts.get(0);
        assertEquals(op.getId(), conflict.getOperation().getId());

        // 9. Resolve Conflict using CREATE_CONFLICT_COPY
        conflictService.resolveConflict(
                user.getId(),
                conflict.getOperation().getId(),
                new com.filesync.conflict.dto.ConflictResolutionRequest(
                        com.filesync.conflict.dto.ConflictResolutionRequest.ResolutionStrategy.CREATE_CONFLICT_COPY));

        // Verify conflict copy was created alongside original
        List<FileEntry> files = fileRepository.findByUserIdAndParentFolderIsNullAndDeletedFalse(user.getId());
        assertEquals(2, files.size(), "Expected 2 files: original and conflict copy");

        Optional<FileEntry> conflictCopyOpt = files.stream()
                .filter(f -> f.getName().contains(" (conflict "))
                .findFirst();
        assertTrue(conflictCopyOpt.isPresent(), "Conflict copy should exist");

        // 10. GC: create an orphaned chunk (no references) and verify it gets cleaned up
        com.filesync.chunk.entity.Chunk orphan = new com.filesync.chunk.entity.Chunk("orphanHash", 1024L, "orphanKey");
        orphan.setCreatedAt(LocalDateTime.now().minusHours(13));
        chunkRepository.save(orphan);

        // Manually update updated_at to bypass Hibernate @UpdateTimestamp
        jdbcTemplate.update("UPDATE chunks SET updated_at = ? WHERE hash = ?", 
            java.sql.Timestamp.valueOf(LocalDateTime.now().minusHours(13)), "orphanHash");

        // Mock storage deletion for GC
        Mockito.doNothing().when(storageServiceClient).deleteChunk(any());

        orphanChunkCleanupService.cleanupOrphanChunks();

        // Verify orphan chunk deleted, but original chunk remains (it has file_version_chunks references)
        assertTrue(chunkRepository.findByHash(realHash).isPresent(),
                "Original chunk must NOT be GC'd (has active file version references)");
        assertFalse(chunkRepository.findByHash("orphanHash").isPresent(),
                "Orphan chunk must be GC'd (no references)");
    }
}
