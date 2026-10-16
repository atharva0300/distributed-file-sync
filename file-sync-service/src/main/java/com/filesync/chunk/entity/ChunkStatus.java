package com.filesync.chunk.entity;

public enum ChunkStatus {
    /** Metadata row created; S3 upload in progress. Stale STAGING rows are cleaned up by GC. */
    STAGING,
    /** S3 upload confirmed. Chunk is safe to reference from FileVersion or UploadSession. */
    ACTIVE,
    /** GC has marked this chunk for physical deletion. */
    DELETING
}
