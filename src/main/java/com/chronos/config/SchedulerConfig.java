package com.chronos.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Wiring for the execution engine: the timer that runs the loops, the bounded pool that
 * performs dispatches, and the shared HTTP client underneath them.
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {

    /**
     * The pool that runs outbound HTTP calls.
     *
     * <p><b>New concept — why the poller must not do the HTTP call itself:</b> claiming rows
     * happens inside a database transaction. If the same thread then made the HTTP call, the
     * transaction would stay open for the whole call, holding row locks for up to
     * {@code timeout_sec} and pinning a connection from a pool of 20. Claiming commits first,
     * then the work is handed to this pool, so locks are held for milliseconds.
     *
     * <p><b>Why bounded, and why AbortPolicy:</b> the queue has a hard limit, and when it is
     * full the pool <em>rejects</em> rather than silently growing or making the caller run the
     * task. The poller catches that rejection and puts the row back to QUEUED, so the work
     * returns to the pool of claimable rows where an idle node can take it. An unbounded queue
     * would instead let a saturated node hoard work it cannot start.
     */
    @Bean("dispatchExecutor")
    public ThreadPoolTaskExecutor dispatchExecutor(SchedulerProperties properties) {
        SchedulerProperties.Dispatch dispatch = properties.dispatch();

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(dispatch.corePoolSize());
        executor.setMaxPoolSize(dispatch.maxPoolSize());
        executor.setQueueCapacity(dispatch.queueCapacity());
        executor.setThreadNamePrefix("chronos-dispatch-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());

        // On shutdown, stop accepting new work but let in-flight calls finish. Without this the
        // JVM could exit mid-request, leaving rows stuck in RUNNING for the reaper to clean up.
        // M6 turns this into a proper graceful-shutdown story.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        executor.initialize();
        return executor;
    }

    /**
     * One HTTP client for the whole application, so connections are pooled and reused across
     * jobs and dispatch threads.
     *
     * <p><b>Redirects are not followed.</b> A 302 is a way to turn an allowed public URL into a
     * request to an internal address after validation has already passed — the same SSRF hole
     * {@link com.chronos.job.JobValidator} guards the original URL against. A redirect is
     * recorded as the 3xx response it is; if a job wants the new location, its owner can point
     * it there.
     */
    @Bean
    public HttpClient dispatchHttpClient(SchedulerProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSec()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
