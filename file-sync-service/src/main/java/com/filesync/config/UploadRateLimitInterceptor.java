package com.filesync.config;

import com.filesync.security.UserPrincipal;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Per-user, per-process rate limiting of the upload API, split by request class (see
 * {@link UploadRateLimitProperties}). This is abuse protection for a single instance, not a
 * distributed quota; bandwidth shaping belongs at the reverse proxy.
 */
@Slf4j
@Component
public class UploadRateLimitInterceptor implements HandlerInterceptor {

    enum RequestClass { TRANSFER, LINK, CONTROL }

    private static final Pattern CHUNK_UPLOAD = Pattern.compile("^/api/upload/\\d+/chunks/\\d+$");
    private static final Pattern CHUNK_LINK = Pattern.compile("^/api/upload/\\d+/chunks/\\d+/link$");

    private record Key(Long userId, RequestClass requestClass) {}

    private static final class Entry {
        final Bucket bucket;
        volatile long lastUsedNanos = System.nanoTime();

        Entry(Bucket bucket) {
            this.bucket = bucket;
        }
    }

    private final UploadRateLimitProperties properties;
    private final Map<Key, Entry> buckets = new ConcurrentHashMap<>();

    public UploadRateLimitInterceptor(UploadRateLimitProperties properties) {
        this.properties = properties;
    }

    static RequestClass classify(String method, String path) {
        if ("POST".equals(method)) {
            if (CHUNK_UPLOAD.matcher(path).matches()) {
                return RequestClass.TRANSFER;
            }
            if (CHUNK_LINK.matcher(path).matches()) {
                return RequestClass.LINK;
            }
        }
        return RequestClass.CONTROL;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            return true; // unauthenticated requests are rejected by the security filter chain
        }

        RequestClass requestClass = classify(request.getMethod(), request.getRequestURI());
        Entry entry = buckets.computeIfAbsent(new Key(principal.getId(), requestClass), k -> new Entry(newBucket(requestClass)));
        entry.lastUsedNanos = System.nanoTime();

        ConsumptionProbe probe = entry.bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return true;
        }
        long retryAfterSeconds = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds() + 1);
        log.warn("RATE_LIMITED: userId={}, class={}, retryAfter={}s", principal.getId(), requestClass, retryAfterSeconds);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType("application/json");
        response.getWriter().write("{\"error\": \"Too many " + requestClass.name().toLowerCase()
                + " requests. Retry after " + retryAfterSeconds + "s.\"}");
        return false;
    }

    private Bucket newBucket(RequestClass requestClass) {
        int perMinute = switch (requestClass) {
            case TRANSFER -> properties.getTransfer().getPerMinute();
            case LINK -> properties.getLink().getPerMinute();
            case CONTROL -> properties.getControl().getPerMinute();
        };
        return Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(perMinute).refillGreedy(perMinute, Duration.ofMinutes(1)).build())
                .build();
    }

    @Scheduled(fixedDelayString = "${app.upload.rate-limit.eviction-interval-ms:300000}")
    public void evictIdleBuckets() {
        long cutoff = System.nanoTime() - Duration.ofMinutes(properties.getIdleEvictionMinutes()).toNanos();
        buckets.values().removeIf(e -> e.lastUsedNanos - cutoff < 0);
    }

    int trackedBuckets() {
        return buckets.size();
    }
}
