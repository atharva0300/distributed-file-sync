package com.filesync.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InternalRequestSigner")
class InternalRequestSignerTest {

    // Same vector is asserted in storage-service's InternalSignatureTest: if either side's
    // canonical form drifts, one of the two tests fails.
    static final String VECTOR_SIGNATURE = "l5M5aNlxT6sAoTnRgFYf7NvpewlRvDLNDb85xOvf9Kw=";

    InternalRequestSigner signer = new InternalRequestSigner("test-secret");

    @Test
    void matchesSharedGoldenVector() {
        var h = signer.sign("POST", "/internal/chunks/abc", null, "hello".getBytes(),
                1700000000000L, "00112233445566778899aabbccddeeff");
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", h.bodySha256());
        assertEquals(VECTOR_SIGNATURE, h.signature());
    }

    @Test
    void signatureCoversMethodPathQueryBodyAndNonce() {
        var base = signer.sign("POST", "/internal/chunks/abc", null, "hello".getBytes(), 1L, "n1n1n1n1n1n1n1n1");
        assertNotEquals(base.signature(), signer.sign("DELETE", "/internal/chunks/abc", null, "hello".getBytes(), 1L, "n1n1n1n1n1n1n1n1").signature());
        assertNotEquals(base.signature(), signer.sign("POST", "/internal/chunks/abd", null, "hello".getBytes(), 1L, "n1n1n1n1n1n1n1n1").signature());
        assertNotEquals(base.signature(), signer.sign("POST", "/internal/chunks/abc", "x=1", "hello".getBytes(), 1L, "n1n1n1n1n1n1n1n1").signature());
        assertNotEquals(base.signature(), signer.sign("POST", "/internal/chunks/abc", null, "hellO".getBytes(), 1L, "n1n1n1n1n1n1n1n1").signature());
        assertNotEquals(base.signature(), signer.sign("POST", "/internal/chunks/abc", null, "hello".getBytes(), 2L, "n1n1n1n1n1n1n1n1").signature());
        assertNotEquals(base.signature(), signer.sign("POST", "/internal/chunks/abc", null, "hello".getBytes(), 1L, "n2n2n2n2n2n2n2n2").signature());
    }

    @Test
    void eachCallUsesAFreshNonce() {
        assertNotEquals(signer.sign("GET", "/x", null, null).nonce(), signer.sign("GET", "/x", null, null).nonce());
    }

    @Test
    void blankSecretIsRejected() {
        assertThrows(IllegalStateException.class, () -> new InternalRequestSigner(" "));
    }
}
