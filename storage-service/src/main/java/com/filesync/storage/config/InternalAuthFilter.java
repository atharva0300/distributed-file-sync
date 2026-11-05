package com.filesync.storage.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authenticates calls to {@code /internal/**} from file-sync-service.
 *
 * <p>The HMAC covers service id, HTTP method, raw path and query, timestamp, a single-use
 * nonce and the SHA-256 of the body (see {@link InternalSignature}). Requests are rejected if
 * the timestamp is more than {@code MAX_SKEW_MS} away from now in either direction, or if the
 * nonce was already seen inside that window.
 *
 * <p>Body binding: the signature covers the <em>declared</em> {@code X-Content-SHA256}. For
 * {@code POST /internal/chunks/{hash}} the declared value must equal {@code {hash}} and the
 * storage layer verifies the streamed bytes against that same hash, so body integrity is enforced
 * without buffering chunks here. Body-less requests must declare the empty-body hash.
 *
 * <p>The nonce cache is per instance; replay across multiple storage-service instances within the
 * skew window would need a shared store (not required for the current single-instance deployment).
 */
@Slf4j
@Component
public class InternalAuthFilter extends OncePerRequestFilter {

    static final long MAX_SKEW_MS = 60_000;

    private final String apiKey;
    private final Clock clock;
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();

    @Autowired
    public InternalAuthFilter(@Value("${app.internal.api-key}") String apiKey) {
        this(apiKey, Clock.systemUTC());
    }

    InternalAuthFilter(String apiKey, Clock clock) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("app.internal.api-key must be configured");
        }
        this.apiKey = apiKey;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String uri = request.getRequestURI();
        if (!uri.startsWith("/internal/") || uri.equals("/internal/health")) {
            chain.doFilter(request, response);
            return;
        }

        String serviceId = request.getHeader("X-Service-Id");
        String timestampStr = request.getHeader("X-Timestamp");
        String nonce = request.getHeader("X-Nonce");
        String bodyHash = request.getHeader("X-Content-SHA256");
        String signature = request.getHeader("X-Signature");

        if (serviceId == null || timestampStr == null || nonce == null || bodyHash == null || signature == null
                || nonce.length() < 16 || nonce.length() > 128) {
            reject(request, response, "Missing or malformed authentication headers");
            return;
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampStr);
        } catch (NumberFormatException e) {
            reject(request, response, "Invalid timestamp format");
            return;
        }

        long now = clock.millis();
        if (Math.abs(now - timestamp) > MAX_SKEW_MS) {
            reject(request, response, "Timestamp outside allowed window");
            return;
        }

        String expected = InternalSignature.sign(apiKey, InternalSignature.canonical(
                serviceId, request.getMethod(), request.getRequestURI(), request.getQueryString(),
                timestamp, nonce, bodyHash));
        if (!InternalSignature.matches(expected, signature)) {
            reject(request, response, "Invalid signature");
            return;
        }

        // Body binding (see class javadoc). Checked after the signature so the value is trusted.
        String requiredBodyHash = requiredBodyHash(request);
        if (!bodyHash.equals(requiredBodyHash)) {
            reject(request, response, "Content hash does not match request");
            return;
        }

        // Replay protection: only record the nonce once the request is otherwise authentic.
        evictExpiredNonces(now);
        if (seenNonces.putIfAbsent(nonce, timestamp + MAX_SKEW_MS) != null) {
            reject(request, response, "Replayed request");
            return;
        }

        chain.doFilter(request, response);
    }

    private static String requiredBodyHash(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if ("POST".equals(request.getMethod()) && uri.startsWith("/internal/chunks/")) {
            return uri.substring("/internal/chunks/".length());
        }
        return InternalSignature.EMPTY_BODY_SHA256;
    }

    private void evictExpiredNonces(long now) {
        Iterator<Map.Entry<String, Long>> it = seenNonces.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() < now) {
                it.remove();
            }
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String reason) throws IOException {
        log.warn("Unauthorized internal access attempt to {} {}: {}", request.getMethod(), request.getRequestURI(), reason);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\": \"Unauthorized\"}");
    }
}
