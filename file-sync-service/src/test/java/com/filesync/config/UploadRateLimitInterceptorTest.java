package com.filesync.config;

import com.filesync.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UploadRateLimitInterceptorTest {

    private final UploadRateLimitProperties props = new UploadRateLimitProperties();
    private final UploadRateLimitInterceptor interceptor = new UploadRateLimitInterceptor(props);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void login(long id) {
        UserPrincipal p = new UserPrincipal(id, "u" + id + "@x.io", "pw", List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p, null, List.of()));
    }

    private MockHttpServletResponse call(String method, String path) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean ok = interceptor.preHandle(new MockHttpServletRequest(method, path), res, new Object());
        if (ok) res.setStatus(200);
        return res;
    }

    @Test
    void classifiesRequests() {
        assertEquals(UploadRateLimitInterceptor.RequestClass.TRANSFER, UploadRateLimitInterceptor.classify("POST", "/api/upload/5/chunks/3"));
        assertEquals(UploadRateLimitInterceptor.RequestClass.LINK, UploadRateLimitInterceptor.classify("POST", "/api/upload/5/chunks/3/link"));
        assertEquals(UploadRateLimitInterceptor.RequestClass.CONTROL, UploadRateLimitInterceptor.classify("POST", "/api/upload/init"));
        assertEquals(UploadRateLimitInterceptor.RequestClass.CONTROL, UploadRateLimitInterceptor.classify("GET", "/api/upload/5/chunks/3"));
    }

    @Test
    void rejectsWith429AndRetryAfterOnceBucketEmpty() throws Exception {
        props.getControl().setPerMinute(3);
        login(1);
        for (int i = 0; i < 3; i++) assertEquals(200, call("POST", "/api/upload/init").getStatus());
        MockHttpServletResponse res = call("POST", "/api/upload/init");
        assertEquals(429, res.getStatus());
        assertNotNull(res.getHeader("Retry-After"));
        assertTrue(Long.parseLong(res.getHeader("Retry-After")) >= 1);
    }

    @Test
    void classesAndUsersHaveIndependentBuckets() throws Exception {
        props.getControl().setPerMinute(1);
        props.getTransfer().setPerMinute(2);
        login(1);
        assertEquals(200, call("POST", "/api/upload/init").getStatus());
        assertEquals(429, call("POST", "/api/upload/init").getStatus());
        // exhausted control budget does not throttle chunk transfers
        assertEquals(200, call("POST", "/api/upload/9/chunks/0").getStatus());
        assertEquals(200, call("POST", "/api/upload/9/chunks/1").getStatus());
        assertEquals(429, call("POST", "/api/upload/9/chunks/2").getStatus());
        // another user is unaffected
        login(2);
        assertEquals(200, call("POST", "/api/upload/init").getStatus());
    }

    @Test
    void idleBucketsAreEvicted() throws Exception {
        props.setIdleEvictionMinutes(0);
        login(1);
        call("POST", "/api/upload/init");
        assertEquals(1, interceptor.trackedBuckets());
        Thread.sleep(5);
        interceptor.evictIdleBuckets();
        assertEquals(0, interceptor.trackedBuckets());
    }
}
