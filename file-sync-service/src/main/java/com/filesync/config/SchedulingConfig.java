package com.filesync.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Separate, bounded thread pools so that a long-running sync operation (e.g. completing a large
 * upload) cannot starve maintenance jobs (lease recovery, session expiry, garbage collection):
 *
 * <ul>
 *   <li>{@code taskScheduler} - runs every {@code @Scheduled} method; these are all short.</li>
 *   <li>{@code syncWorkerExecutor} - runs claimed sync operations; size = max concurrent operations
 *       (each uses at most one DB connection at a time, so keep it well below the Hikari pool size).</li>
 *   <li>{@code syncHeartbeatScheduler} - renews leases of running operations.</li>
 * </ul>
 *
 * Spring shuts all three down with the application context.
 */
@Configuration
public class SchedulingConfig {

    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler(@Value("${app.scheduling.maintenance-threads:4}") int threads) {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(threads);
        s.setThreadNamePrefix("maintenance-");
        s.setWaitForTasksToCompleteOnShutdown(true);
        s.setAwaitTerminationSeconds(30);
        return s;
    }

    @Bean(name = "syncWorkerExecutor")
    public ThreadPoolTaskExecutor syncWorkerExecutor(@Value("${app.sync.background-worker.threads:2}") int threads) {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(threads);
        e.setMaxPoolSize(threads);
        e.setQueueCapacity(0); // SyncWorker only submits as many operations as it has free slots
        e.setThreadNamePrefix("sync-worker-");
        e.setWaitForTasksToCompleteOnShutdown(true);
        e.setAwaitTerminationSeconds(30);
        return e;
    }

    @Bean(name = "syncHeartbeatScheduler")
    public ThreadPoolTaskScheduler syncHeartbeatScheduler(@Value("${app.sync.background-worker.threads:2}") int threads) {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(Math.max(1, threads));
        s.setThreadNamePrefix("sync-heartbeat-");
        s.setRemoveOnCancelPolicy(true);
        return s;
    }
}
