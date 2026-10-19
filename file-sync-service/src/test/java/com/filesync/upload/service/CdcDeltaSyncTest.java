package com.filesync.upload.service;

import com.filesync.auth.entity.User;
import com.filesync.auth.repository.UserRepository;
import com.filesync.chunk.repository.ChunkRepository;
import com.filesync.chunking.ChunkingProperties;
import com.filesync.chunking.ContentDefinedChunkingStrategy;
import com.filesync.chunking.ChunkBoundary;
import com.filesync.device.entity.Device;
import com.filesync.device.repository.DeviceRepository;
import com.filesync.file.entity.FileEntry;
import com.filesync.file.repository.FileRepository;
import com.filesync.upload.dto.DeltaRequest;
import com.filesync.upload.entity.UploadSession;
import com.filesync.upload.repository.UploadSessionRepository;
import com.filesync.version.entity.FileVersion;
import com.filesync.version.entity.FileVersionChunk;
import com.filesync.version.repository.FileVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class CdcDeltaSyncTest {

    @Autowired private UploadService uploadService;
    @Autowired private UserRepository userRepository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private FileRepository fileRepository;
    @Autowired private FileVersionRepository versionRepository;
    @Autowired private ChunkRepository chunkRepository;
    @Autowired private UploadSessionRepository sessionRepository;

    @Test
    public void testComputeDeltaCDC() throws Exception {
        // Setup user, device, file
        User user = userRepository.save(new User("cdc_test@example.com", "pass"));
        Device device = deviceRepository.save(new Device(user, "Device", com.filesync.device.entity.DeviceType.DESKTOP, "token"));
        FileEntry file = fileRepository.save(new FileEntry(user, null, "test.txt", false));

        // Generate data and CDC boundaries
        byte[] original = new byte[20000];
        new Random(42).nextBytes(original);
        
        ContentDefinedChunkingStrategy cdc = new ContentDefinedChunkingStrategy(256, 512, 1024);
        List<ChunkBoundary> origBoundaries = cdc.chunk(new ByteArrayInputStream(original), original.length);

        // Fake a completed v1 using original data
        FileVersion v1 = new FileVersion(file, 1, "fakehash", (long) original.length, device, "UPLOAD", null, "CDC");
        v1 = versionRepository.save(v1);

        for (int i = 0; i < origBoundaries.size(); i++) {
            ChunkBoundary b = origBoundaries.get(i);
            com.filesync.chunk.entity.Chunk chunk = new com.filesync.chunk.entity.Chunk(b.sha256Hash(), (long) b.size(), "key" + i);
            chunk.setStatus(com.filesync.chunk.entity.ChunkStatus.ACTIVE);
            chunk = chunkRepository.save(chunk);
            v1.getChunks().add(new FileVersionChunk(v1, i, chunk, (long) b.size()));
        }
        versionRepository.save(v1);

        file.setCurrentVersion(1);
        file.setCurrentVersionId(v1);
        fileRepository.save(file);

        // Modify file: insert byte at beginning
        byte[] modified = new byte[original.length + 1];
        modified[0] = 0x7F;
        System.arraycopy(original, 0, modified, 1, original.length);

        List<ChunkBoundary> modBoundaries = cdc.chunk(new ByteArrayInputStream(modified), modified.length);
        List<DeltaRequest.ChunkManifestEntry> clientManifest = modBoundaries.stream()
                .map(b -> new DeltaRequest.ChunkManifestEntry(b.index(), b.sha256Hash(), (long) b.size()))
                .toList();

        // Create a new session
        UploadSession session = new UploadSession(file, device, 1, 2, (long) modified.length, "newhash", 512L, java.time.LocalDateTime.now().plusHours(1), "CDC");
        session = sessionRepository.save(session);

        // Compute delta!
        List<String> missingHashes = uploadService.computeDelta(user.getId(), file.getId(), session.getId(), 1, (long) modified.length, clientManifest);

        // Since it's CDC, we expect very few missing hashes (only the first few chunks should change)
        assertTrue(missingHashes.size() > 0, "Should have at least 1 missing chunk");
        assertTrue(missingHashes.size() < modBoundaries.size() / 2, "Should reuse most chunks");
        
        // Also check if totalChunks got updated
        UploadSession reloaded = sessionRepository.findById(session.getId()).orElseThrow();
        assertEquals(clientManifest.size(), reloaded.getTotalChunks());
    }
}
