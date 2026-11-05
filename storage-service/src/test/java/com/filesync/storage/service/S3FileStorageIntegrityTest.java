package com.filesync.storage.service;

import com.filesync.storage.exception.ChunkHashMismatchException;
import com.filesync.storage.util.HashUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("S3FileStorageService — upload integrity (B4 regressions)")
class S3FileStorageIntegrityTest {

    static final byte[] GOOD = "good chunk bytes".getBytes(StandardCharsets.UTF_8);
    static final byte[] BAD = "tampered bytes!!".getBytes(StandardCharsets.UTF_8);
    static final String HASH = HashUtil.sha256Hex(GOOD);

    private S3Client s3 = mock(S3Client.class);
    private S3FileStorageService storage = new S3FileStorageService(s3, "bucket");

    @Test
    @DisplayName("invalid upload of a new chunk never reaches S3")
    void invalidNewChunkNeverPut() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());

        assertThrows(ChunkHashMismatchException.class,
                () -> storage.storeChunk(HASH, new ByteArrayInputStream(BAD), BAD.length));

        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("invalid upload under an existing hash neither overwrites nor deletes the stored object")
    void invalidUploadDoesNotTouchExisting() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());

        assertThrows(ChunkHashMismatchException.class,
                () -> storage.storeChunk(HASH, new ByteArrayInputStream(BAD), BAD.length));

        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("valid new chunk is written once with If-None-Match")
    void validChunkPut() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());

        String key = storage.storeChunk(HASH, new ByteArrayInputStream(GOOD), GOOD.length);

        assertEquals("chunks/" + HASH.substring(0, 2) + "/" + HASH, key);
        verify(s3, times(1)).putObject(argThat((PutObjectRequest r) -> "*".equals(r.ifNoneMatch())), any(RequestBody.class));
    }

    @Test
    @DisplayName("losing a concurrent write race (HTTP 412) is treated as deduplication")
    void precondFailedIsDedup() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(412)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("PreconditionFailed").build()).build());

        assertDoesNotThrow(() -> storage.storeChunk(HASH, new ByteArrayInputStream(GOOD), GOOD.length));
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("S3 failure during promotion is reported as StorageException")
    void putFailureReported() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(500).message("boom").build());

        assertThrows(com.filesync.storage.exception.StorageException.class,
                () -> storage.storeChunk(HASH, new ByteArrayInputStream(GOOD), GOOD.length));
    }
}
