package com.filesync.storage.util;

import com.filesync.storage.exception.ChunkHashMismatchException;
import com.filesync.storage.exception.StorageException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Streams an upload into a temporary file while hashing it, and only reports success if
 * the bytes match the content-addressed key and the declared length. Nothing is ever
 * written under the final key until this has succeeded, so a bad upload can neither
 * publish corrupt data nor disturb an already-stored chunk.
 */
public final class VerifiedSpool {

    private VerifiedSpool() {}

    /** Copies {@code in} to {@code target} and verifies hash and length. Deletes {@code target} on failure. */
    public static void spoolAndVerify(InputStream in, Path target, String expectedHash, long expectedLength) throws IOException {
        boolean ok = false;
        try {
            MessageDigest digest = newDigest();
            long copied;
            try (DigestInputStream dis = new DigestInputStream(in, digest);
                 OutputStream out = Files.newOutputStream(target)) {
                copied = dis.transferTo(out);
            }
            check(copied, HexFormat.of().formatHex(digest.digest()), expectedHash, expectedLength);
            ok = true;
        } finally {
            if (!ok) {
                Files.deleteIfExists(target);
            }
        }
    }

    /** Hashes {@code in} without storing it; used when the chunk is already present. */
    public static void verifyOnly(InputStream in, String expectedHash, long expectedLength) throws IOException {
        MessageDigest digest = newDigest();
        long copied;
        try (DigestInputStream dis = new DigestInputStream(in, digest)) {
            copied = dis.transferTo(OutputStream.nullOutputStream());
        }
        check(copied, HexFormat.of().formatHex(digest.digest()), expectedHash, expectedLength);
    }

    private static void check(long copied, String actualHash, String expectedHash, long expectedLength) {
        if (copied != expectedLength) {
            throw new ChunkHashMismatchException(
                    "Body length " + copied + " does not match Content-Length " + expectedLength);
        }
        if (!actualHash.equals(expectedHash)) {
            throw new ChunkHashMismatchException(
                    "SHA-256 hash mismatch. Expected: " + expectedHash + ", Calculated: " + actualHash);
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new StorageException("SHA-256 algorithm not available", e);
        }
    }
}
