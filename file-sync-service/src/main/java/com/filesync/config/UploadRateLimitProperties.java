package com.filesync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-user request limits for {@code /api/upload/**}, in requests per minute, by request class.
 * Chunk transfers, cheap "link existing chunk" calls and metadata/control calls are limited
 * separately so that a legitimate large upload (thousands of chunk requests) is never throttled
 * by limits sized for control traffic.
 */
@ConfigurationProperties(prefix = "app.upload.rate-limit")
public class UploadRateLimitProperties {

    private Limit transfer = new Limit(600);
    private Limit link = new Limit(3000);
    private Limit control = new Limit(300);
    /** Buckets idle for this long are dropped so the per-user map cannot grow without bound. */
    private long idleEvictionMinutes = 15;

    public static class Limit {
        private int perMinute;

        public Limit() {}

        public Limit(int perMinute) {
            this.perMinute = perMinute;
        }

        public int getPerMinute() { return perMinute; }
        public void setPerMinute(int perMinute) { this.perMinute = perMinute; }
    }

    public Limit getTransfer() { return transfer; }
    public void setTransfer(Limit transfer) { this.transfer = transfer; }
    public Limit getLink() { return link; }
    public void setLink(Limit link) { this.link = link; }
    public Limit getControl() { return control; }
    public void setControl(Limit control) { this.control = control; }
    public long getIdleEvictionMinutes() { return idleEvictionMinutes; }
    public void setIdleEvictionMinutes(long m) { this.idleEvictionMinutes = m; }
}
