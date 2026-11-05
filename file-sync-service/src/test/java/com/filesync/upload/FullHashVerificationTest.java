package com.filesync.upload;

import com.filesync.FileSyncApplication;
import com.filesync.auth.entity.User;
import com.filesync.auth.repository.UserRepository;
import com.filesync.client.StorageServiceClient;
import com.filesync.client.dto.ChunkStorageResponse;
import com.filesync.common.util.HashUtil;
import com.filesync.device.entity.Device;
import com.filesync.device.entity.DeviceType;
import com.filesync.device.repository.DeviceRepository;
import com.filesync.file.entity.FileEntry;
import com.filesync.file.repository.FileRepository;
import com.filesync.upload.service.UploadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static com.filesync.testsupport.UploadTestSupport.uploadSingleChunk;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Completion only re-reads chunks from storage for files up to the configured verification threshold. */
@SpringBootTest(classes = FileSyncApplication.class)
@ActiveProfiles("test")
class FullHashVerificationTest {

    static final String WRONG_FILE_HASH = "f".repeat(64);

    @Autowired UploadService uploadService;
    @Autowired UserRepository userRepository;
    @Autowired DeviceRepository deviceRepository;
    @Autowired FileRepository fileRepository;
    @MockBean StorageServiceClient storage;

    private Long sessionWithWrongFileHash(User user, Device device, FileEntry file, byte[] data) {
        String chunkHash = HashUtil.sha256Hex(data);
        when(storage.storeChunk(anyString(), any(byte[].class)))
                .thenReturn(new ChunkStorageResponse(chunkHash, "chunks/x/" + chunkHash, false, (long) data.length));
        when(storage.readChunk(chunkHash)).thenAnswer(i -> new ByteArrayInputStream(data));
        Long sessionId = uploadService.initUpload(user.getId(), file.getId(), device.getId(), 0,
                (long) data.length, WRONG_FILE_HASH).sessionId();
        uploadSingleChunk(uploadService, user.getId(), file.getId(), sessionId, 0, chunkHash, data);
        return sessionId;
    }

    private void setThreshold(long bytes) {
        Object target = AopTestUtils.<Object>getUltimateTargetObject(uploadService);
        ReflectionTestUtils.setField(target, "fullHashVerifyMaxBytes", bytes);
    }

    private Object[] fixture(String tag) {
        User u = userRepository.save(new User(tag + UUID.randomUUID() + "@example.com", "pw"));
        Device d = deviceRepository.save(new Device(u, "d", DeviceType.DESKTOP, UUID.randomUUID().toString()));
        FileEntry f = fileRepository.save(new FileEntry(u, null, "f-" + UUID.randomUUID(), false));
        return new Object[]{u, d, f};
    }

    @Test
    void smallFileWithWrongDeclaredHashIsRejected() {
        setThreshold(1024 * 1024);
        Object[] x = fixture("small");
        Long session = sessionWithWrongFileHash((User) x[0], (Device) x[1], (FileEntry) x[2], "small file content".getBytes());

        assertThrows(IllegalArgumentException.class, () -> uploadService.completeUpload(((User) x[0]).getId(), session, null));
    }

    @Test
    void fileAboveThresholdIsNotReReadFromStorage() {
        setThreshold(4);
        Object[] x = fixture("large");
        byte[] data = "content larger than the 4 byte threshold".getBytes();
        Long session = sessionWithWrongFileHash((User) x[0], (Device) x[1], (FileEntry) x[2], data);

        assertDoesNotThrow(() -> uploadService.completeUpload(((User) x[0]).getId(), session, null));

        verify(storage, never()).readChunk(anyString());
        assertEquals(1, fileRepository.findById(((FileEntry) x[2]).getId()).orElseThrow().getCurrentVersion());
    }
}
