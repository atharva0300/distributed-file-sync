package com.filesync.storage.service;

import java.io.InputStream;

public interface FileStorageService {

    /**
     * Store a chunk from an InputStream without buffering it entirely in memory.
     *
     * @param chunkHash     SHA-256 hex string
     * @param stream        chunk data stream
     * @param contentLength expected length of the chunk
     * @return storage key or relative path
     */
    String storeChunk(String chunkHash, InputStream stream, long contentLength);

    /**
     * Check whether a chunk already exists in storage.
     *
     * @param chunkHash SHA-256 hex string
     * @return true if chunk is already stored
     */
    boolean chunkExists(String chunkHash);

    /**
     * Read a chunk by its SHA-256 hash.
     *
     * @param chunkHash SHA-256 hex string
     * @return InputStream of chunk data
     */
    InputStream readChunk(String chunkHash);

    /**
     * Delete a chunk by its SHA-256 hash.
     *
     * @param chunkHash SHA-256 hex string
     */
    void deleteChunk(String chunkHash);
}

