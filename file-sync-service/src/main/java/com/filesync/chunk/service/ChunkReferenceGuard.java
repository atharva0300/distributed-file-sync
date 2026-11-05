package com.filesync.chunk.service;

import com.filesync.chunk.entity.Chunk;
import com.filesync.chunk.entity.ChunkStatus;
import com.filesync.chunk.repository.ChunkRepository;
import com.filesync.common.exception.ConflictException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Must be called, inside the transaction that inserts {@code file_version_chunks} rows, before
 * those rows are created. It share-locks the chunk rows and verifies every one is ACTIVE, which
 * makes "a chunk referenced by a committed file version is never physically deleted" hold:
 * garbage collection needs an exclusive lock on the chunk row to start deleting, so it is ordered
 * strictly before or after this transaction.
 */
@Component
public class ChunkReferenceGuard {

    private final ChunkRepository chunkRepository;

    public ChunkReferenceGuard(ChunkRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockAndVerifyActive(Collection<Long> chunkIds) {
        Set<Long> distinct = new HashSet<>(chunkIds);
        if (distinct.isEmpty()) {
            return;
        }
        List<Chunk> chunks = chunkRepository.findAllByIdForShare(distinct);
        if (chunks.size() != distinct.size()) {
            throw new ConflictException("One or more chunks no longer exist; re-upload the missing chunks");
        }
        for (Chunk c : chunks) {
            if (c.getStatus() != ChunkStatus.ACTIVE) {
                throw new ConflictException("Chunk " + c.getHash() + " is " + c.getStatus()
                        + "; re-upload it and complete again");
            }
        }
    }
}
