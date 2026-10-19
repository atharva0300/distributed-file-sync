package com.filesync.integration;

import com.filesync.chunk.entity.Chunk;
import com.filesync.chunk.entity.ChunkStatus;
import com.filesync.chunk.repository.ChunkRepository;
import com.filesync.chunk.service.OrphanChunkCleanupService;
import com.filesync.client.StorageServiceClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest
@ActiveProfiles("test")
public class StagingChunkGCTest {

    @Autowired private ChunkRepository chunkRepository;
    @Autowired private OrphanChunkCleanupService orphanChunkCleanupService;
    @MockBean private StorageServiceClient storageServiceClient;

    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    public void testCleanupStaleStagingChunks() {
        // 1. Create a stale STAGING chunk (> 24h old)
        Chunk staleStaging = new Chunk("staleStagingHash", 1024L, "staging/staleStagingHash");
        staleStaging.setStatus(ChunkStatus.STAGING);
        staleStaging.setCreatedAt(LocalDateTime.now().minusHours(25));
        chunkRepository.save(staleStaging);

        // 2. Create a recent STAGING chunk (< 1h old)
        Chunk recentStaging = new Chunk("recentStagingHash", 1024L, "staging/recentStagingHash");
        recentStaging.setStatus(ChunkStatus.STAGING);
        recentStaging.setCreatedAt(LocalDateTime.now().minusMinutes(30));
        chunkRepository.save(recentStaging);

        // 3. Manually update 'updated_at' to bypass Hibernate @UpdateTimestamp
        jdbcTemplate.update("UPDATE chunks SET updated_at = ? WHERE hash = ?", 
            java.sql.Timestamp.valueOf(LocalDateTime.now().minusHours(25)), "staleStagingHash");
        jdbcTemplate.update("UPDATE chunks SET updated_at = ? WHERE hash = ?", 
            java.sql.Timestamp.valueOf(LocalDateTime.now().minusMinutes(30)), "recentStagingHash");

        // 4. Mock storage delete (though STAGING chunks might not exist on storage, service attempts deletion)
        Mockito.doNothing().when(storageServiceClient).deleteChunk(any());

        // 4. Run GC
        orphanChunkCleanupService.cleanupStaleStagingChunks();

        // 5. Verify stale is deleted, recent remains
        assertFalse(chunkRepository.findByHash("staleStagingHash").isPresent(), "Stale staging chunk should be deleted");
        assertTrue(chunkRepository.findByHash("recentStagingHash").isPresent(), "Recent staging chunk should NOT be deleted");
    }
}
