package com.filesync.integration;

import com.filesync.client.StorageServiceClient;
import com.filesync.file.entity.FileEntry;
import com.filesync.file.repository.FileRepository;
import com.filesync.file.service.FileService;
import com.filesync.upload.service.UploadService;
import com.filesync.auth.entity.User;
import com.filesync.auth.repository.UserRepository;
import com.filesync.device.entity.Device;
import com.filesync.device.repository.DeviceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
public class ZeroByteFileTest {

    @Autowired private UserRepository userRepository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private FileRepository fileRepository;
    @Autowired private UploadService uploadService;
    @Autowired private FileService fileService;
    @MockBean private StorageServiceClient storageServiceClient;

    @Test
    public void testZeroByteFileLifecycle() throws Exception {
        User user = new User("zerobyte@example.com", "password");
        user = userRepository.save(user);

        Device device = new Device(user, "DeviceA", com.filesync.device.entity.DeviceType.DESKTOP, "tokenA");
        deviceRepository.save(device);

        FileEntry file = new FileEntry(user, null, "empty.txt", false);
        file = fileRepository.save(file);

        String emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        // 1. Initialize upload session for 0-byte file
        Long sessionId = uploadService.initUpload(user.getId(), file.getId(), device.getId(), 0, 0L, emptyHash).sessionId();

        // 2. We do NOT upload any chunks for a 0-byte file.
        // 3. Complete the upload session directly.
        uploadService.completeUpload(user.getId(), sessionId, null);

        // 4. Verify the file is advanced to version 1 and has correct metadata
        FileEntry updatedFile = fileRepository.findById(file.getId()).orElseThrow();
        assertEquals(1, updatedFile.getCurrentVersion(), "File version should advance to 1");
        assertEquals(0L, updatedFile.getFileSize(), "File size should be 0");
        assertEquals(emptyHash, updatedFile.getFileHash(), "File hash should be the empty SHA-256 hash");

        // 5. Check missing chunks (should return empty list)
        var missingChunks = uploadService.getMissingChunks(user.getId(), sessionId);
        assertEquals(0, missingChunks.size(), "Should have no missing chunks for 0-byte file");

        // 6. Download manifest (should have empty chunk hashes)
        var manifest = fileService.getDownloadManifest(user.getId(), file.getId());
        assertEquals(0, manifest.chunkHashes().size(), "Download manifest should have 0 chunks");
        assertEquals(0L, manifest.fileSize(), "Manifest size should be 0");
    }
}
