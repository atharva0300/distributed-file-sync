package com.filesync.chunking;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ContentDefinedChunkingStrategyTest {

    // Small parameters for testing
    private final int minSize = 256;
    private final int targetSize = 512;
    private final int maxSize = 1024;
    private final ContentDefinedChunkingStrategy cdc = new ContentDefinedChunkingStrategy(minSize, targetSize, maxSize);

    @Test
    void testDeterminism() throws IOException {
        byte[] data = generateRandomData(5000);
        List<ChunkBoundary> b1 = cdc.chunk(new ByteArrayInputStream(data), data.length);
        List<ChunkBoundary> b2 = cdc.chunk(new ByteArrayInputStream(data), data.length);

        assertEquals(b1.size(), b2.size());
        for (int i = 0; i < b1.size(); i++) {
            assertEquals(b1.get(i).size(), b2.get(i).size());
            assertEquals(b1.get(i).sha256Hash(), b2.get(i).sha256Hash());
            assertEquals(b1.get(i).offset(), b2.get(i).offset());
        }
    }

    @Test
    void testBoundsEnforced() throws IOException {
        byte[] data = generateRandomData(10000);
        List<ChunkBoundary> boundaries = cdc.chunk(new ByteArrayInputStream(data), data.length);

        assertTrue(boundaries.size() > 1);

        long totalSize = 0;
        for (int i = 0; i < boundaries.size(); i++) {
            ChunkBoundary b = boundaries.get(i);
            totalSize += b.size();

            if (i < boundaries.size() - 1) {
                // Non-final chunk must be between min and max
                assertTrue(b.size() >= minSize, "Chunk too small: " + b.size());
                assertTrue(b.size() <= maxSize, "Chunk too large: " + b.size());
            } else {
                // Final chunk can be smaller than min
                assertTrue(b.size() <= maxSize, "Final chunk too large: " + b.size());
            }
        }
        assertEquals(data.length, totalSize);
    }

    @Test
    void testEmptyFile() throws IOException {
        List<ChunkBoundary> boundaries = cdc.chunk(new ByteArrayInputStream(new byte[0]), 0);
        assertTrue(boundaries.isEmpty());
    }

    @Test
    void testSmallFile() throws IOException {
        byte[] data = generateRandomData(100); // smaller than minSize
        List<ChunkBoundary> boundaries = cdc.chunk(new ByteArrayInputStream(data), data.length);
        
        assertEquals(1, boundaries.size());
        assertEquals(data.length, boundaries.get(0).size());
        assertEquals(0, boundaries.get(0).offset());
        assertEquals(computeHash(data), boundaries.get(0).sha256Hash());
    }

    @Test
    void testInsertAtBeginningResynchronizes() throws IOException {
        byte[] original = generateRandomData(10000);
        List<ChunkBoundary> origBoundaries = cdc.chunk(new ByteArrayInputStream(original), original.length);

        // Insert 1 byte at the beginning
        byte[] modified = new byte[original.length + 1];
        modified[0] = (byte) 0xFF;
        System.arraycopy(original, 0, modified, 1, original.length);

        List<ChunkBoundary> modBoundaries = cdc.chunk(new ByteArrayInputStream(modified), modified.length);

        // With fixed chunking, an insert at the beginning shifts EVERY chunk.
        // With CDC, the first chunk will change, but subsequent chunks should resynchronize and match.
        // Count how many hashes from the original are preserved in the modified.
        long preservedChunks = origBoundaries.stream()
                .map(ChunkBoundary::sha256Hash)
                .filter(hash -> modBoundaries.stream().anyMatch(mb -> mb.sha256Hash().equals(hash)))
                .count();

        // At least half of the chunks should be preserved (usually much more)
        assertTrue(preservedChunks >= origBoundaries.size() / 2,
                "Expected most chunks to be preserved, but got " + preservedChunks + " out of " + origBoundaries.size());
    }
    
    @Test
    void testReconstruction() throws IOException {
        byte[] data = generateRandomData(20000);
        List<ChunkBoundary> boundaries = cdc.chunk(new ByteArrayInputStream(data), data.length);
        
        byte[] reconstructed = new byte[data.length];
        for (ChunkBoundary b : boundaries) {
            System.arraycopy(data, (int)b.offset(), reconstructed, (int)b.offset(), b.size());
        }
        
        assertArrayEquals(data, reconstructed);
    }

    private byte[] generateRandomData(int size) {
        byte[] data = new byte[size];
        new Random(42).nextBytes(data); // Seeded for reproducibility
        return data;
    }

    private String computeHash(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
