package com.chronos.scheduler;

import com.chronos.config.SchedulerProperties;
import com.chronos.job.HttpMethodType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Performs the outbound HTTP call for one execution and classifies the result.
 *
 * <p>This class deliberately knows nothing about the database. It takes a description of a
 * request, makes it, and returns a {@link DispatchOutcome} — which makes the failure
 * classification rules (the part worth getting right) testable without Postgres.
 */
@Component
public class HttpDispatcher {

    private static final Logger log = LoggerFactory.getLogger(HttpDispatcher.class);

    /** Sent on every request so a receiver can de-duplicate our at-least-once delivery. */
    public static final String IDEMPOTENCY_HEADER = "X-Idempotency-Key";

    private final HttpClient httpClient;
    private final SchedulerProperties properties;

    /**
     * One RestClient per distinct job timeout.
     *
     * <p><b>Why a cache:</b> a read timeout belongs to the request factory, not to an individual
     * RestClient call, so "30s for this job, 120s for that one" cannot be expressed on a shared
     * client. Building a client per <em>dispatch</em> would throw away connection pooling, so
     * instead there is one per distinct timeout value, all sharing the same underlying
     * {@link HttpClient} and therefore the same connection pool. Bounded in practice: the column
     * only allows 1–300, and real deployments use a handful of values.
     */
    private final Map<Integer, RestClient> clientsByTimeout = new ConcurrentHashMap<>();

    public HttpDispatcher(HttpClient httpClient, SchedulerProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    /**
     * Makes the call. Never throws for an HTTP-level problem — a failure is a return value here,
     * because "the target returned 500" is normal operation for a scheduler, not an exception.
     */
    public DispatchOutcome dispatch(DispatchRequest request) {
        try {
            RestClient.RequestBodySpec spec = clientFor(request.timeoutSec())
                    .method(HttpMethod.valueOf(request.method().name()))
                    // URI.create, not the String overload: RestClient treats a String uri as a
                    // template and would try to expand any {...} in the job's URL.
                    .uri(java.net.URI.create(request.url()))
                    .headers(headers -> {
                        if (request.headers() != null) {
                            request.headers().forEach(headers::add);
                        }
                        // Set last so a job can never override it (JobValidator also rejects it
                        // at creation time — belt and braces, because this is a guarantee we
                        // make to the receiving system, not merely a convenience).
                        headers.set(IDEMPOTENCY_HEADER, String.valueOf(request.executionId()));
                        if (request.body() != null) {
                            headers.setContentType(MediaType.APPLICATION_JSON);
                        }
                    });

            if (request.body() != null) {
                spec = spec.body(request.body());
            }

            // Suppress the default error handler: without this, RestClient throws on any
            // 4xx/5xx and we would lose the status code and body we want to record.
            ResponseEntity<String> response = spec.exchange((req, res) -> ResponseEntity
                    .status(res.getStatusCode())
                    .body(readBody(res.getBody())));

            return classify(response.getStatusCode(), response.getBody());

        } catch (Exception e) {
            // Connection refused, DNS failure, read timeout, TLS problem. None of these produced
            // a response, so there is no status code to record — but all are transient in
            // principle, so they are retryable.
            String message = describe(e);
            log.debug("Dispatch of execution {} failed without a response: {}",
                    request.executionId(), message);
            return DispatchOutcome.retryableFailure(null, null, message);
        }
    }

    /**
     * The retry policy in one place.
     *
     * <ul>
     *   <li><b>2xx</b> — succeeded.</li>
     *   <li><b>4xx</b> — the request is wrong and will stay wrong; permanent.</li>
     *   <li><b>5xx and everything else</b> — the target is unhealthy; worth another attempt.</li>
     * </ul>
     *
     * <p>Note 429 (Too Many Requests) is 4xx and therefore treated as permanent here, even
     * though it is genuinely transient. Honest simplification: the spec defines the rule as
     * "4xx is non-retryable", and special-casing 429 properly means honouring Retry-After, which
     * belongs with the backoff work in M5.
     */
    private DispatchOutcome classify(HttpStatusCode status, String body) {
        int code = status.value();

        if (status.is2xxSuccessful()) {
            return DispatchOutcome.success(code, body);
        }
        if (status.is4xxClientError()) {
            return DispatchOutcome.permanentFailure(code, body, "HTTP " + code + " (not retryable)");
        }
        return DispatchOutcome.retryableFailure(code, body, "HTTP " + code);
    }

    private RestClient clientFor(int timeoutSec) {
        return clientsByTimeout.computeIfAbsent(timeoutSec, seconds -> {
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
            // The per-job timeout_sec is a read timeout: how long we wait for the target to
            // respond. The connect timeout lives on the shared HttpClient.
            factory.setReadTimeout(Duration.ofSeconds(seconds));
            return RestClient.builder().requestFactory(factory).build();
        });
    }

    /**
     * Reads at most {@code responseSnippetLimit} bytes — the body could be enormous, and we
     * only ever want enough to debug with. Truncating on a byte boundary can clip a multi-byte
     * UTF-8 character into a replacement char; acceptable for a debugging snippet.
     */
    private String readBody(java.io.InputStream stream) throws java.io.IOException {
        if (stream == null) {
            return null;
        }
        int limit = properties.responseSnippetLimit();
        byte[] bytes = stream.readNBytes(limit);
        if (bytes.length == 0) {
            return null;
        }
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Exception messages can be null or unhelpfully bare; always give the operator the type. */
    private String describe(Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /**
     * Everything the dispatcher needs, with no JPA entity in sight — see the note on
     * {@link ExecutionClaimService#claimBatch} about entities crossing thread boundaries.
     */
    public record DispatchRequest(
            Long executionId,
            String url,
            HttpMethodType method,
            Map<String, String> headers,
            String body,
            int timeoutSec
    ) {
    }
}
