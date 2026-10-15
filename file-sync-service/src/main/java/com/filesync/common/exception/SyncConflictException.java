package com.filesync.common.exception;

public class SyncConflictException extends RuntimeException {
    private final Long fileId;
    private final int clientBaseVersion;
    private final int serverCurrentVersion;

    public SyncConflictException(Long fileId, int clientBaseVersion, int serverCurrentVersion) {
        super(String.format("Sync conflict on file %d: client base version %d, server version %d",
                fileId, clientBaseVersion, serverCurrentVersion));
        this.fileId = fileId;
        this.clientBaseVersion = clientBaseVersion;
        this.serverCurrentVersion = serverCurrentVersion;
    }

    public Long getFileId() { return fileId; }
    public int getClientBaseVersion() { return clientBaseVersion; }
    public int getServerCurrentVersion() { return serverCurrentVersion; }
}
