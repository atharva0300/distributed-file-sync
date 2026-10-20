package com.filesync.storage.service;

import com.filesync.storage.exception.StorageException;
import com.filesync.storage.util.HashUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.InputStream;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
public class S3FileStorageService implements FileStorageService {

    private final S3Client s3Client;
    private final String bucketName;

    public S3FileStorageService(
            S3Client s3Client,
            @Value("${app.aws.s3.bucket:filesync-chunks}") String bucketName) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        log.info("Initialized S3FileStorageService with bucket: {}", bucketName);
    }

    @Override
    public String storeChunk(String chunkHash, InputStream stream, long contentLength) {
        HashUtil.validateHash(chunkHash);
        String s3Key = resolveChunkKey(chunkHash);

        try {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType("application/octet-stream")
                    .contentLength(contentLength)
                    .ifNoneMatch("*")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromInputStream(stream, contentLength));
            log.debug("Stored streaming chunk in S3: hash={}, key={}, size={}", chunkHash, s3Key, contentLength);
            return s3Key;
        } catch (S3Exception e) {
            if (e.statusCode() == 412 || "PreconditionFailed".equals(e.awsErrorDetails().errorCode())) {
                log.debug("Streaming chunk already exists in S3 (atomic dedup): hash={}, key={}", chunkHash, s3Key);
                return s3Key;
            }
            throw new StorageException("Failed to store streaming chunk in S3: " + chunkHash, e);
        } catch (Exception e) {
            throw new StorageException("Failed to store streaming chunk in S3: " + chunkHash, e);
        }
    }

    @Override
    public boolean chunkExists(String chunkHash) {
        HashUtil.validateHash(chunkHash);
        String s3Key = resolveChunkKey(chunkHash);
        try {
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build();
            s3Client.headObject(headRequest);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw new StorageException("Error checking chunk existence in S3: " + chunkHash, e);
        }
    }

    @Override
    public InputStream readChunk(String chunkHash) {
        HashUtil.validateHash(chunkHash);
        String s3Key = resolveChunkKey(chunkHash);
        try {
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build();
            return s3Client.getObject(getRequest);
        } catch (NoSuchKeyException e) {
            throw new StorageException("Chunk not found in S3: " + chunkHash, e);
        } catch (Exception e) {
            throw new StorageException("Failed to read chunk from S3: " + chunkHash, e);
        }
    }

    @Override
    public void deleteChunk(String chunkHash) {
        HashUtil.validateHash(chunkHash);
        String s3Key = resolveChunkKey(chunkHash);
        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build();
            s3Client.deleteObject(deleteRequest);
            log.debug("Deleted chunk from S3: {}", chunkHash);
        } catch (Exception e) {
            throw new StorageException("Failed to delete chunk from S3: " + chunkHash, e);
        }
    }

    private String resolveChunkKey(String chunkHash) {
        String prefix = chunkHash.substring(0, 2);
        return "chunks/" + prefix + "/" + chunkHash;
    }
}

