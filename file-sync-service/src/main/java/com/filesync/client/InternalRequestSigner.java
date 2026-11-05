package com.filesync.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Builds the authentication headers for calls to storage-service.
 *
 * <p>The canonical string must stay byte-for-byte identical to storage-service's
 * {@code InternalSignature.canonical}:
 * <pre>
 *   v1\n{serviceId}\n{METHOD}\n{rawPath}\n{rawQuery or empty}\n{timestampMillis}\n{nonce}\n{bodySha256Hex}
 * </pre>
 * A shared test vector in both modules guards against drift.
 */
public final class InternalRequestSigner {

    public static final String SERVICE_ID = "file-sync-service";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String secret;

    public InternalRequestSigner(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("app.internal.api-key must be configured");
        }
        this.secret = secret;
    }

    public record SignedHeaders(String serviceId, long timestamp, String nonce, String bodySha256, String signature) {}

    public SignedHeaders sign(String method, String rawPath, String rawQuery, byte[] body) {
        byte[] nonceBytes = new byte[16];
        RANDOM.nextBytes(nonceBytes);
        return sign(method, rawPath, rawQuery, body, System.currentTimeMillis(),
                HexFormat.of().formatHex(nonceBytes));
    }

    SignedHeaders sign(String method, String rawPath, String rawQuery, byte[] body, long timestamp, String nonce) {
        String bodyHash = sha256Hex(body == null ? new byte[0] : body);
        String canonical = canonical(SERVICE_ID, method, rawPath, rawQuery, timestamp, nonce, bodyHash);
        return new SignedHeaders(SERVICE_ID, timestamp, nonce, bodyHash, hmac(canonical));
    }

    static String canonical(String serviceId, String method, String rawPath, String rawQuery,
                            long timestampMillis, String nonce, String bodySha256Hex) {
        return "v1\n" + serviceId + "\n" + method.toUpperCase() + "\n" + rawPath + "\n"
                + (rawQuery == null ? "" : rawQuery) + "\n" + timestampMillis + "\n" + nonce + "\n" + bodySha256Hex;
    }

    private String hmac(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256", e);
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
