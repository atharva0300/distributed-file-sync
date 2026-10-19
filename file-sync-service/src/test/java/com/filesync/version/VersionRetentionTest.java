package com.filesync.version;

import com.filesync.FileSyncApplication;
import com.filesync.auth.entity.User;
import com.filesync.auth.repository.UserRepository;
import com.filesync.device.entity.Device;
import com.filesync.device.repository.DeviceRepository;
import com.filesync.file.entity.FileEntry;
import com.filesync.file.repository.FileRepository;
import com.filesync.version.entity.FileVersion;
import com.filesync.version.repository.FileVersionRepository;
import com.filesync.version.service.VersionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(classes = FileSyncApplication.class)
@ActiveProfiles("test")
public class VersionRetentionTest {

    @Autowired private VersionService versionService;
    @Autowired private FileVersionRepository versionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private FileRepository fileRepository;

    @Test
    public void testVersionCleanup() {
        User user = userRepository.save(new User("retention@example.com", "password"));
        Device device = deviceRepository.save(new Device(user, "DeviceA", com.filesync.device.entity.DeviceType.DESKTOP, "tokenA"));
        FileEntry file = fileRepository.save(new FileEntry(user, null, "test_retention.txt", false));

        // Create 25 versions. We want to keep 20.
        ReflectionTestUtils.setField(versionService, "maxVersionsPerFile", 20);

        for (int i = 1; i <= 25; i++) {
            file.setCurrentVersion(i);
            file = fileRepository.saveAndFlush(file);
            FileVersion fv = new FileVersion(file, i, "hash" + i, 100L, device, "UPLOAD", null);
            versionRepository.save(fv);
        }

        // Now run cleanup
        versionService.cleanupOldVersions();

        // 5 versions should be deleted
        List<FileVersion> remaining = versionRepository.findByFileIdOrderByVersionNumberDesc(file.getId());
        assertEquals(20, remaining.size(), "Should only keep 20 versions");
        
        // Ensure the oldest versions are gone (1 to 5)
        assertEquals(25, remaining.get(0).getVersionNumber(), "Latest version is 25");
        assertEquals(6, remaining.get(remaining.size() - 1).getVersionNumber(), "Oldest kept version is 6");
    }
}
