package com.filesync.config;

import com.filesync.security.UserPrincipal;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class UploadRateLimitInterceptor implements HandlerInterceptor {

    private final Map<Long, Bucket> userBuckets = new ConcurrentHashMap<>();
    
    @Value("${app.upload.rate-limit.capacity:100}")
    private int capacity;
    
    @Value("${app.upload.rate-limit.refill-tokens:100}")
    private int refillTokens;
    
    @Value("${app.upload.rate-limit.refill-duration-minutes:1}")
    private int refillDurationMinutes;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!request.getRequestURI().startsWith("/api/upload")) {
            return true; // Only rate-limit upload endpoints
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            // Unauthenticated requests should be caught by security config, but if they reach here, allow or reject.
            return true; 
        }

        Bucket bucket = userBuckets.computeIfAbsent(principal.getId(), this::createNewBucket);

        if (bucket.tryConsume(1)) {
            return true;
        } else {
            log.warn("Rate limit exceeded for user {}", principal.getId());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Too many upload requests. Please try again later.\"}");
            return false;
        }
    }

    private Bucket createNewBucket(Long userId) {
        Bandwidth limit = Bandwidth.classic(capacity, Refill.greedy(refillTokens, Duration.ofMinutes(refillDurationMinutes)));
        return Bucket.builder().addLimit(limit).build();
    }
}
