package com.chronos.stats;

import com.chronos.security.ChronosUserDetails;
import com.chronos.stats.dto.JobStatsResponse;
import com.chronos.stats.dto.StatsResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only health and throughput numbers.
 *
 * <p>Two routes on two prefixes, following {@code ExecutionController}: the system summary is its
 * own resource, while a job's statistics are a sub-resource of that job.
 *
 * <p><b>Why this is not an actuator endpoint.</b> {@code /actuator/*} answers "is this process
 * healthy" for a load balancer, and is usually locked down or unexposed. These numbers answer
 * "is my scheduling healthy" for a person, they are scoped per user by the same ownership rules
 * as the rest of the API, and the dashboard renders them. That makes them application data, not
 * operational telemetry, and they belong on the same authenticated API as everything else.
 */
@RestController
public class StatsController {

    private static final int DEFAULT_WINDOW_HOURS = 24;

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /**
     * System summary, scoped to what the caller can see.
     *
     * <p>{@code windowHours} is clamped rather than validated in the service: an out-of-range
     * value here is a dashboard picking a silly number, and quietly giving them the nearest
     * sensible window is friendlier than a 400 on a page that only wanted to draw a chart.
     */
    @GetMapping("/stats")
    public StatsResponse overview(
            @AuthenticationPrincipal ChronosUserDetails caller,
            @RequestParam(defaultValue = "" + DEFAULT_WINDOW_HOURS) int windowHours) {
        return statsService.overview(caller, windowHours);
    }

    @GetMapping("/jobs/{jobId}/stats")
    public JobStatsResponse forJob(@PathVariable Long jobId,
                                   @AuthenticationPrincipal ChronosUserDetails caller) {
        return statsService.forJob(jobId, caller);
    }
}
