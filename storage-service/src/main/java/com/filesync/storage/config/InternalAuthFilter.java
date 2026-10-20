package com.filesync.storage.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
public class InternalAuthFilter extends OncePerRequestFilter {

    private final String apiKey;
    private static final long MAX_AGE_MS = 5 * 60 * 1000; // 5 minutes

    public InternalAuthFilter(@Value("${app.internal.api-key:dev-internal-key}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // Only apply to /internal/** routes
        if (!request.getRequestURI().startsWith("/internal/")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Allow health check without auth
        if (request.getRequestURI().equals("/internal/health")) {
            filterChain.doFilter(request, response);
            return;
        }

        String serviceId = request.getHeader("X-Service-Id");
        String timestampStr = request.getHeader("X-Timestamp");
        String signature = request.getHeader("X-Signature");

        if (serviceId == null || timestampStr == null || signature == null) {
            reject(request, response, "Missing authentication headers");
            return;
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampStr);
        } catch (NumberFormatException e) {
            reject(request, response, "Invalid timestamp format");
            return;
        }

        if (System.currentTimeMillis() - timestamp > MAX_AGE_MS) {
            reject(request, response, "Request expired");
            return;
        }

        String payload = serviceId + ":" + timestamp;
        String expectedSignature = computeHmacSha256(payload, this.apiKey);

        if (!java.security.MessageDigest.isEqual(expectedSignature.getBytes(), signature.getBytes())) {
            reject(request, response, "Invalid signature");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String reason) throws IOException {
        log.warn("Unauthorized internal access attempt to {}: {}", request.getRequestURI(), reason);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\": \"Unauthorized - " + reason + "\"}");
    }

    private String computeHmacSha256(String data, String key) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec secretKeySpec = new javax.crypto.spec.SecretKeySpec(key.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC", e);
        }
    }
}

