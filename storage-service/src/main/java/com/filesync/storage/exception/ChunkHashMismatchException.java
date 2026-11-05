package com.filesync.storage.exception;

/** Uploaded bytes do not match the content-addressed key (or declared length). Maps to HTTP 400. */
public class ChunkHashMismatchException extends IllegalArgumentException {
    public ChunkHashMismatchException(String message) {
        super(message);
    }
}
