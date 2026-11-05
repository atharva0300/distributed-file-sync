package com.filesync.storage.service;

import com.filesync.storage.exception.StorageException;
import com.filesync.storage.util.HashUtil;
import com.filesync.storage.util.VerifiedSpool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorageService implements FileStorageService {

    private final Path basePath;

    public LocalFileStorageService(@Value("${app.storage.path:./storage}") String storagePath) {
        this.basePath = Paths.get(storagePath).toAbsolutePath().normalize();
        initializeStorageDirectory();
    }

    @Override
    public String storeChunk(String chunkHash, InputStream stream, long contentLength) {
        HashUtil.validateHash(chunkHash);
        Path chunkPath = resolveChunkPath(chunkHash);

        try {
            Files.createDirectories(chunkPath.getParent());

            if (Files.isRegularFile(chunkPath)) {
                // Already stored: still verify what was sent, but never touch the stored object.
                VerifiedSpool.verifyOnly(stream, chunkHash, contentLength);
                return relativize(chunkPath);
            }

            // Write to a temp file in the same directory (same filesystem => atomic rename),
            // verify, and only then publish under the content-addressed name.
            Path tempPath = Files.createTempFile(chunkPath.getParent(), chunkHash, ".tmp");
            try {
                VerifiedSpool.spoolAndVerify(stream, tempPath, chunkHash, contentLength);
                Files.move(tempPath, chunkPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                log.debug("Stored chunk atomically: hash={}, size={}", chunkHash, contentLength);
            } finally {
                Files.deleteIfExists(tempPath);
            }

            return relativize(chunkPath);
        } catch (IOException e) {
            throw new StorageException("Failed to store chunk: " + chunkHash, e);
        }
    }

    @Override
    public boolean chunkExists(String chunkHash) {
        HashUtil.validateHash(chunkHash);
        return Files.isRegularFile(resolveChunkPath(chunkHash));
    }

    @Override
    public InputStream readChunk(String chunkHash) {
        HashUtil.validateHash(chunkHash);
        Path chunkPath = resolveChunkPath(chunkHash);

        if (!Files.isRegularFile(chunkPath)) {
            throw new StorageException("Chunk not found: " + chunkHash);
        }

        try {
            return Files.newInputStream(chunkPath);
        } catch (IOException e) {
            throw new StorageException("Failed to read chunk: " + chunkHash, e);
        }
    }

    @Override
    public void deleteChunk(String chunkHash) {
        HashUtil.validateHash(chunkHash);
        Path chunkPath = resolveChunkPath(chunkHash);

        try {
            Files.deleteIfExists(chunkPath);
            log.debug("Deleted chunk: {}", chunkHash);
        } catch (IOException e) {
            throw new StorageException("Failed to delete chunk: " + chunkHash, e);
        }
    }

    private Path resolveChunkPath(String chunkHash) {
        String prefix = chunkHash.substring(0, 2);
        return basePath.resolve("chunks").resolve(prefix).resolve(chunkHash);
    }

    private String relativize(Path absolutePath) {
        return basePath.relativize(absolutePath).toString();
    }

    private void initializeStorageDirectory() {
        try {
            Files.createDirectories(basePath.resolve("chunks"));
            log.info("Storage initialized at: {}", basePath);
        } catch (IOException e) {
            throw new StorageException("Failed to initialize storage directory: " + basePath, e);
        }
    }
}

