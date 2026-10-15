package com.filesync.common.util;

import com.filesync.common.exception.StorageException;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 hashing utility.
 *
 * Content-addressable storage identity: two byte sequences with identical
 * SHA-256 hashes are treated as the same physical content — the basis for
 * chunk deduplication.
 */
public final class HashUtil {

    private static final int BUFFER_SIZE = 8192;

    private HashUtil() {}

    /** Compute SHA-256 of a byte array and return lowercase hex string. */
    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new StorageException("SHA-256 algorithm not available", e);
        }
    }

    /** Compute SHA-256 of a UTF-8 string and return lowercase hex string. */
    public static String sha256(String text) {
        return sha256Hex(text != null ? text.getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[0]);
    }

    /** Compute SHA-256 of an InputStream and return lowercase hex string.
     *  The stream is fully consumed but NOT closed by this method. */
    public static String sha256Hex(InputStream stream) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            return bytesToHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new StorageException("SHA-256 algorithm not available", e);
        } catch (IOException e) {
            throw new StorageException("IO error during hashing", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
