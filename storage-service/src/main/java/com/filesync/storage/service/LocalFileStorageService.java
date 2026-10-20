package com.filesync.storage.service;

import com.filesync.storage.exception.StorageException;
import com.filesync.storage.util.HashUtil;
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

            if (Files.exists(chunkPath)) {
                return relativize(chunkPath);
            }

            Path tempPath = Files.createTempFile(chunkPath.getParent(), chunkHash, ".tmp");
            try {
                Files.copy(stream, tempPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.move(tempPath, chunkPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                log.debug("Stored streaming chunk atomically: hash={}, path={}, size={}", chunkHash, chunkPath, contentLength);
            } catch (java.nio.file.FileAlreadyExistsException e) {
                log.debug("Chunk already exists (race condition handled safely): {}", chunkHash);
            } finally {
                Files.deleteIfExists(tempPath);
            }

            return relativize(chunkPath);
        } catch (IOException e) {
            throw new StorageException("Failed to store streaming chunk: " + chunkHash, e);
        }
    }

    @Override
    public boolean chunkExists(String chunkHash) {
        HashUtil.validateHash(chunkHash);
        return Files.exists(resolveChunkPath(chunkHash));
    }

    @Override
    public InputStream readChunk(String chunkHash) {
        HashUtil.validateHash(chunkHash);
        Path chunkPath = resolveChunkPath(chunkHash);

        if (!Files.exists(chunkPath)) {
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

