package com.filesync.upload.entity;

public enum UploadSessionStatus {
    INITIATED,
    UPLOADING,
    FINALIZING,
    COMPLETED,
    ABORTED,
    EXPIRED
}
