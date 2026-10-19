package com.filesync.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for SHA-256 hashing utility.
 *
 * These verify the core identity function for content-addressable storage.
 * Identical content MUST produce identical hashes — this is the deduplication guarantee.
 */
class HashUtilTest {

    @Test
    void sha256OfEmptyBytes_isKnownConstant() {
        String hash = HashUtil.sha256Hex(new byte[]{});
        assertThat(hash).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void sha256OfKnownString_isCorrect() {
        byte[] data = "hello world".getBytes();
        String hash = HashUtil.sha256Hex(data);
        assertThat(hash).isEqualTo("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9");
    }

    @Test
    void identicalContent_producesIdenticalHash() {
        byte[] data1 = "chunk content for deduplication test".getBytes();
        byte[] data2 = "chunk content for deduplication test".getBytes();
        assertThat(HashUtil.sha256Hex(data1)).isEqualTo(HashUtil.sha256Hex(data2));
    }

    @Test
    void differentContent_producesDifferentHash() {
        byte[] data1 = "chunk version A".getBytes();
        byte[] data2 = "chunk version B".getBytes();
        assertThat(HashUtil.sha256Hex(data1)).isNotEqualTo(HashUtil.sha256Hex(data2));
    }

    @Test
    void hash_is64CharHexString() {
        String hash = HashUtil.sha256Hex("any content".getBytes());
        assertThat(hash).matches("[0-9a-f]{64}");
    }

    @Test
    void sha256InputStream_matchesByteArray() throws Exception {
        byte[] data = "stream hashing test".getBytes();
        String fromBytes = HashUtil.sha256Hex(data);
        String fromStream = HashUtil.sha256Hex(new java.io.ByteArrayInputStream(data));
        assertThat(fromBytes).isEqualTo(fromStream);
    }
}
