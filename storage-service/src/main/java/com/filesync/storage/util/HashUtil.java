package com.filesync.storage.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class HashUtil {

    private HashUtil() {}

    public static String sha256Hex(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("Data cannot be null");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    public static void validateHash(String hash) {
        if (hash == null || !hash.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("Invalid SHA-256 hash: " + hash);
        }
    }
}

