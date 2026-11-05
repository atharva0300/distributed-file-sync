package com.filesync.storage.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InternalAuthFilter — HMAC request authentication")
class InternalAuthFilterTest {

    static final String SECRET = "test-secret";
    static final long NOW = 1_700_000_000_000L;
    static final String HASH = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"; // sha256("hello")

    InternalAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new InternalAuthFilter(SECRET, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("shared golden vector matches file-sync-service's signer")
    void goldenVector() {
        String canonical = InternalSignature.canonical("file-sync-service", "POST", "/internal/chunks/abc", null,
                1700000000000L, "00112233445566778899aabbccddeeff", HASH);
        assertEquals("l5M5aNlxT6sAoTnRgFYf7NvpewlRvDLNDb85xOvf9Kw=", InternalSignature.sign(SECRET, canonical));
    }

    /** Builds a request signed for {@code (method, path)} with the given timestamp/nonce/body-hash. */
    private MockHttpServletRequest signed(String method, String path, long ts, String nonce, String bodyHash) {
        String sig = InternalSignature.sign(SECRET,
                InternalSignature.canonical("file-sync-service", method, path, null, ts, nonce, bodyHash));
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.addHeader("X-Service-Id", "file-sync-service");
        req.addHeader("X-Timestamp", String.valueOf(ts));
        req.addHeader("X-Nonce", nonce);
        req.addHeader("X-Content-SHA256", bodyHash);
        req.addHeader("X-Signature", sig);
        return req;
    }

    private int run(MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        return chain.getRequest() != null ? 200 : res.getStatus();
    }

    private static String nonce(int i) {
        return String.format("%032x", i);
    }

    @Test
    void validRequestPasses() throws Exception {
        assertEquals(200, run(signed("GET", "/internal/chunks/" + HASH + "/exists", NOW, nonce(1), InternalSignature.EMPTY_BODY_SHA256)));
    }

    @Test
    void validChunkUploadPassesWhenDeclaredHashEqualsPathHash() throws Exception {
        assertEquals(200, run(signed("POST", "/internal/chunks/" + HASH, NOW, nonce(2), HASH)));
    }

    @Test
    void healthEndpointNeedsNoAuth() throws Exception {
        assertEquals(200, run(new MockHttpServletRequest("GET", "/internal/health")));
    }

    @Test
    void missingHeadersRejected() throws Exception {
        assertEquals(401, run(new MockHttpServletRequest("GET", "/internal/chunks/" + HASH)));
    }

    @Test
    void modifiedMethodRejected() throws Exception {
        MockHttpServletRequest req = signed("GET", "/internal/chunks/" + HASH, NOW, nonce(3), InternalSignature.EMPTY_BODY_SHA256);
        req.setMethod("DELETE");
        assertEquals(401, run(req));
    }

    @Test
    void modifiedPathRejected() throws Exception {
        MockHttpServletRequest req = signed("DELETE", "/internal/chunks/" + HASH, NOW, nonce(4), InternalSignature.EMPTY_BODY_SHA256);
        req.setRequestURI("/internal/chunks/" + "0".repeat(64));
        assertEquals(401, run(req));
    }

    @Test
    void modifiedBodyHashHeaderRejected() throws Exception {
        MockHttpServletRequest req = signed("POST", "/internal/chunks/" + HASH, NOW, nonce(5), HASH);
        req.removeHeader("X-Content-SHA256");
        req.addHeader("X-Content-SHA256", "0".repeat(64));
        assertEquals(401, run(req));
    }

    @Test
    void validlySignedButWrongBodyHashForChunkUploadRejected() throws Exception {
        assertEquals(401, run(signed("POST", "/internal/chunks/" + HASH, NOW, nonce(6), "0".repeat(64))));
    }

    @Test
    void bodyHashDeclaredOnBodylessCallRejected() throws Exception {
        assertEquals(401, run(signed("DELETE", "/internal/chunks/" + HASH, NOW, nonce(7), HASH)));
    }

    @Test
    void expiredTimestampRejected() throws Exception {
        long old = NOW - InternalAuthFilter.MAX_SKEW_MS - 1;
        assertEquals(401, run(signed("GET", "/internal/chunks/" + HASH + "/exists", old, nonce(8), InternalSignature.EMPTY_BODY_SHA256)));
    }

    @Test
    void futureTimestampRejected() throws Exception {
        long future = NOW + InternalAuthFilter.MAX_SKEW_MS + 1;
        assertEquals(401, run(signed("GET", "/internal/chunks/" + HASH + "/exists", future, nonce(9), InternalSignature.EMPTY_BODY_SHA256)));
    }

    @Test
    void farFutureSignatureRejected() throws Exception {
        long nextYear = NOW + 365L * 24 * 3600 * 1000;
        assertEquals(401, run(signed("DELETE", "/internal/chunks/" + HASH, nextYear, nonce(10), InternalSignature.EMPTY_BODY_SHA256)));
    }

    @Test
    void replayedRequestRejected() throws Exception {
        String path = "/internal/chunks/" + HASH + "/exists";
        assertEquals(200, run(signed("GET", path, NOW, nonce(11), InternalSignature.EMPTY_BODY_SHA256)));
        assertEquals(401, run(signed("GET", path, NOW, nonce(11), InternalSignature.EMPTY_BODY_SHA256)));
    }

    @Test
    void wrongSecretRejected() throws Exception {
        String path = "/internal/chunks/" + HASH + "/exists";
        String sig = InternalSignature.sign("other-secret",
                InternalSignature.canonical("file-sync-service", "GET", path, null, NOW, nonce(12), InternalSignature.EMPTY_BODY_SHA256));
        MockHttpServletRequest req = signed("GET", path, NOW, nonce(12), InternalSignature.EMPTY_BODY_SHA256);
        req.removeHeader("X-Signature");
        req.addHeader("X-Signature", sig);
        assertEquals(401, run(req));
    }

    @Test
    void blankSecretFailsFast() {
        assertThrows(IllegalStateException.class, () -> new InternalAuthFilter("", Clock.systemUTC()));
    }
}
