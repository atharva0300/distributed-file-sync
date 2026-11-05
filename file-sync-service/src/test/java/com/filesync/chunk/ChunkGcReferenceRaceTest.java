package com.filesync.chunk;

import com.filesync.FileSyncApplication;
import com.filesync.auth.entity.User;
import com.filesync.auth.repository.UserRepository;
import com.filesync.chunk.entity.Chunk;
import com.filesync.chunk.entity.ChunkStatus;
import com.filesync.chunk.repository.ChunkRepository;
import com.filesync.chunk.service.ChunkReferenceGuard;
import com.filesync.chunk.service.OrphanChunkCleanupService;
import com.filesync.client.StorageServiceClient;
import com.filesync.common.exception.ConflictException;
import com.filesync.device.entity.Device;
import com.filesync.device.entity.DeviceType;
import com.filesync.device.repository.DeviceRepository;
import com.filesync.file.entity.FileEntry;
import com.filesync.file.repository.FileRepository;
import com.filesync.version.entity.FileVersion;
import com.filesync.version.entity.FileVersionChunk;
import com.filesync.version.repository.FileVersionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** GC vs reference creation: a chunk referenced by a committed file version is never physically deleted. */
@SpringBootTest(classes = FileSyncApplication.class)
@ActiveProfiles("test")
class ChunkGcReferenceRaceTest {

    @Autowired OrphanChunkCleanupService gc;
    @Autowired ChunkReferenceGuard guard;
    @Autowired ChunkRepository chunkRepository;
    @Autowired UserRepository userRepository;
    @Autowired DeviceRepository deviceRepository;
    @Autowired FileRepository fileRepository;
    @Autowired FileVersionRepository versionRepository;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager em;

    @MockBean StorageServiceClient storage;

    private Chunk oldActiveChunk(ChunkStatus status) {
        String hash = String.format("%064x", UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE);
        Chunk c = new Chunk(hash, 10L, "chunks/x/" + hash);
        c.setStatus(status);
        Chunk saved = chunkRepository.saveAndFlush(c);
        age(saved.getId());
        return saved;
    }

    private void age(Long chunkId) {
        tx.executeWithoutResult(s -> em.createNativeQuery(
                "UPDATE chunks SET updated_at = NOW() - INTERVAL '48 hours' WHERE id = :id")
                .setParameter("id", chunkId).executeUpdate());
    }

    private FileEntry newFile() {
        User u = userRepository.save(new User("gc-" + UUID.randomUUID() + "@example.com", "pw"));
        Device d = deviceRepository.save(new Device(u, "dev", DeviceType.DESKTOP, UUID.randomUUID().toString()));
        FileEntry f = fileRepository.save(new FileEntry(u, null, "f-" + UUID.randomUUID(), false));
        return f;
    }

    @Test
    void gcWaitsForInFlightVersionCommitAndThenSkipsTheChunk() throws Exception {
        Chunk chunk = oldActiveChunk(ChunkStatus.ACTIVE);
        FileEntry file = newFile();
        Device device = deviceRepository.findAll().get(0);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // T1: creates a version referencing the chunk; holds the reference-creation tx open for a while.
        CompletableFuture<Void> referencing = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            guard.lockAndVerifyActive(List.of(chunk.getId()));
            locked.countDown();
            try {
                assertTrue(release.await(20, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            FileVersion v = new FileVersion(fileRepository.getReferenceById(file.getId()), 1, "h", 10L,
                    deviceRepository.getReferenceById(device.getId()), "UPLOAD", null, "CDC");
            v.getChunks().add(new FileVersionChunk(v, 0, chunkRepository.getReferenceById(chunk.getId()), 10L));
            versionRepository.save(v);
        }));
        assertTrue(locked.await(20, TimeUnit.SECONDS));

        // T2: GC runs while T1 is in flight; it must block on the chunk row lock.
        CompletableFuture<Void> collecting = CompletableFuture.runAsync(gc::cleanupOrphanChunks);
        Thread.sleep(700);
        assertFalse(collecting.isDone(), "GC must wait for the transaction that is referencing the chunk");

        release.countDown();
        referencing.get(20, TimeUnit.SECONDS);
        collecting.get(20, TimeUnit.SECONDS);

        Chunk after = chunkRepository.findById(chunk.getId()).orElseThrow();
        assertEquals(ChunkStatus.ACTIVE, after.getStatus());
        verify(storage, never()).deleteChunk(chunk.getHash());
    }

    @Test
    void referenceCreationRefusesAChunkThatGcAlreadyMarkedDeleting() {
        Chunk chunk = oldActiveChunk(ChunkStatus.ACTIVE);
        gc.cleanupOrphanChunks(); // no references: collected (storage mock deletes silently)
        verify(storage).deleteChunk(chunk.getHash());

        // Row is gone: reference creation must fail rather than create a dangling reference.
        assertThrows(ConflictException.class,
                () -> tx.executeWithoutResult(s -> guard.lockAndVerifyActive(List.of(chunk.getId()))));
    }

    @Test
    void failedPhysicalDeleteLeavesChunkDeletingNeverActive() {
        Chunk chunk = oldActiveChunk(ChunkStatus.ACTIVE);
        doThrow(new RuntimeException("storage down")).when(storage).deleteChunk(chunk.getHash());

        gc.cleanupOrphanChunks();

        assertEquals(ChunkStatus.DELETING, chunkRepository.findById(chunk.getId()).orElseThrow().getStatus());
        assertThrows(ConflictException.class,
                () -> tx.executeWithoutResult(s -> guard.lockAndVerifyActive(List.of(chunk.getId()))));
    }

    @Test
    void stuckDeletingChunkIsFinishedByRecovery() {
        Chunk chunk = oldActiveChunk(ChunkStatus.ACTIVE);
        doThrow(new RuntimeException("storage down")).doNothing().when(storage).deleteChunk(chunk.getHash());
        gc.cleanupOrphanChunks();
        tx.executeWithoutResult(s -> em.createNativeQuery(
                "UPDATE chunks SET deleting_at = NOW() - INTERVAL '2 hours' WHERE id = :id")
                .setParameter("id", chunk.getId()).executeUpdate());

        gc.recoverStuckDeletingChunks();

        assertTrue(chunkRepository.findById(chunk.getId()).isEmpty());
    }

    @Test
    void retriedUploadRefreshesStagingRowSoItIsNotCollectedAsStale() {
        Chunk staging = oldActiveChunk(ChunkStatus.STAGING);
        chunkRepository.insertAsStaging(staging.getHash(), 10L, "staging/" + staging.getHash()); // retry touches updated_at

        gc.cleanupStaleStagingChunks();

        assertEquals(ChunkStatus.STAGING, chunkRepository.findById(staging.getId()).orElseThrow().getStatus());
        verify(storage, never()).deleteChunk(anyString());
    }

    @Test
    void trulyStaleStagingRowIsCollected() {
        Chunk staging = oldActiveChunk(ChunkStatus.STAGING);

        gc.cleanupStaleStagingChunks();

        verify(storage).deleteChunk(staging.getHash());
        assertTrue(chunkRepository.findById(staging.getId()).isEmpty());
    }
}
