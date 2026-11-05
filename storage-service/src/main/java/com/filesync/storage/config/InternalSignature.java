package com.filesync.storage.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Canonical request signature shared (by specification) with file-sync-service's
 * {@code InternalRequestSigner}. Both sides MUST build the identical string:
 *
 * <pre>
 *   v1\n{serviceId}\n{METHOD}\n{rawPath}\n{rawQuery or empty}\n{timestampMillis}\n{nonce}\n{bodySha256Hex}
 * </pre>
 *
 * signature = Base64( HMAC-SHA256(secret, canonical) ).
 */
public final class InternalSignature {

    public static final String EMPTY_BODY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private InternalSignature() {}

    public static String canonical(String serviceId, String method, String rawPath, String rawQuery,
                                   long timestampMillis, String nonce, String bodySha256Hex) {
        return "v1\n" + serviceId + "\n" + method.toUpperCase() + "\n" + rawPath + "\n"
                + (rawQuery == null ? "" : rawQuery) + "\n" + timestampMillis + "\n" + nonce + "\n" + bodySha256Hex;
    }

    public static String sign(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256", e);
        }
    }

    /** Constant-time comparison of the presented and expected signatures. */
    public static boolean matches(String expected, String presented) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
