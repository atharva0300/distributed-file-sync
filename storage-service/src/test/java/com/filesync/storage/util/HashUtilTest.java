package com.filesync.storage.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("HashUtil Tests — SHA-256 Validation")
class HashUtilTest {

    @Test
    @DisplayName("sha256Hex produces correct 64-char lowercase hex for known input")
    void testKnownHash() {
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
        String hash = HashUtil.sha256Hex(data);

        assertEquals(64, hash.length());
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", hash);
    }

    @Test
    @DisplayName("sha256Hex is deterministic")
    void testDeterminism() {
        byte[] data = "deterministic content".getBytes(StandardCharsets.UTF_8);
        assertEquals(HashUtil.sha256Hex(data), HashUtil.sha256Hex(data));
    }

    @Test
    @DisplayName("validateHash accepts valid 64-character lowercase hex strings")
    void testValidateHashValid() {
        assertDoesNotThrow(() -> HashUtil.validateHash("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"));
    }

    @Test
    @DisplayName("validateHash rejects invalid hashes (length, characters, null)")
    void testValidateHashInvalid() {
        assertThrows(IllegalArgumentException.class, () -> HashUtil.validateHash(null));
        assertThrows(IllegalArgumentException.class, () -> HashUtil.validateHash(""));
        assertThrows(IllegalArgumentException.class, () -> HashUtil.validateHash("short"));
        assertThrows(IllegalArgumentException.class, () -> HashUtil.validateHash("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcdeZ")); // non-hex
    }
}

