package com.chronos.scheduler;

import com.chronos.config.SchedulerProperties;
import com.chronos.job.HttpMethodType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the dispatch and failure-classification rules against a real local HTTP server.
 *
 * <p>No Spring and no Docker — the JDK's built-in {@link HttpServer} is enough to exercise the
 * part that actually matters: deciding whether a given failure is worth retrying.
 */
class HttpDispatcherTest {

    private HttpServer server;
    private String baseUrl;
    private HttpDispatcher dispatcher;

    /** Captures what the server actually received, so we can assert on headers and body. */
    private final AtomicReference<Map<String, String>> receivedHeaders = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedMethod = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/ok", exchange -> respond(exchange, 200, "{\"ok\":true}"));
        server.createContext("/created", exchange -> respond(exchange, 201, ""));
        server.createContext("/bad-request", exchange -> respond(exchange, 400, "nope"));
        server.createContext("/not-found", exchange -> respond(exchange, 404, "missing"));
        server.createContext("/rate-limited", exchange -> respond(exchange, 429, "slow down"));
        server.createContext("/boom", exchange -> respond(exchange, 500, "kaboom"));
        server.createContext("/unavailable", exchange -> respond(exchange, 503, "later"));
        server.createContext("/huge", exchange -> respond(exchange, 200, "x".repeat(5_000)));
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "eventually");
        });

        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        dispatcher = new HttpDispatcher(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                properties(1_000));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static SchedulerProperties properties(int snippetLimit) {
        return new SchedulerProperties(false, 5000, 5000, 50, 100, 2, snippetLimit,
                300, 35,
                new SchedulerProperties.Dispatch(2, 4, 10),
                new SchedulerProperties.Reap(30_000, 30, 50));
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        receivedMethod.set(exchange.getRequestMethod());
        // getFirst() normalises the lookup, so this does not depend on how the server cased the
        // header name on the way in.
        receivedHeaders.set(Map.of(
                HttpDispatcher.IDEMPOTENCY_HEADER,
                String.valueOf(exchange.getRequestHeaders().getFirst(HttpDispatcher.IDEMPOTENCY_HEADER)),
                "X-Tenant",
                String.valueOf(exchange.getRequestHeaders().getFirst("X-Tenant"))));
        receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private HttpDispatcher.DispatchRequest request(String path, HttpMethodType method,
                                                   String body, int timeoutSec) {
        return new HttpDispatcher.DispatchRequest(
                4242L, baseUrl + path, method, Map.of("X-Tenant", "acme"), body, timeoutSec);
    }

    // ---------------------------------------------------------------- success

    @Test
    @DisplayName("a 2xx is a success and the body is kept as a snippet")
    void successOn2xx() {
        DispatchOutcome outcome = dispatcher.dispatch(
                request("/ok", HttpMethodType.POST, "{\"a\":1}", 5));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.retryable()).isFalse();
        assertThat(outcome.responseCode()).isEqualTo(200);
        assertThat(outcome.snippet()).contains("\"ok\":true");
        assertThat(outcome.errorMessage()).isNull();
    }

    @Test
    @DisplayName("a 201 with an empty body is still a success")
    void successOnEmptyBody() {
        DispatchOutcome outcome = dispatcher.dispatch(request("/created", HttpMethodType.POST, "{}", 5));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.responseCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("the execution id is sent as X-Idempotency-Key, and job headers are forwarded")
    void sendsIdempotencyKeyAndJobHeaders() {
        dispatcher.dispatch(request("/ok", HttpMethodType.POST, "{\"a\":1}", 5));

        Map<String, String> headers = receivedHeaders.get();
        assertThat(headers).containsEntry(HttpDispatcher.IDEMPOTENCY_HEADER, "4242");
        assertThat(headers).containsEntry("X-Tenant", "acme");
    }

    @Test
    @DisplayName("the payload is forwarded byte for byte with the configured method")
    void forwardsBodyAndMethod() {
        dispatcher.dispatch(request("/ok", HttpMethodType.PUT, "{\"hello\":\"world\"}", 5));

        assertThat(receivedMethod.get()).isEqualTo("PUT");
        assertThat(receivedBody.get()).isEqualTo("{\"hello\":\"world\"}");
    }

    @Test
    @DisplayName("a GET is sent without a body")
    void getHasNoBody() {
        DispatchOutcome outcome = dispatcher.dispatch(request("/ok", HttpMethodType.GET, null, 5));

        assertThat(outcome.success()).isTrue();
        assertThat(receivedMethod.get()).isEqualTo("GET");
        assertThat(receivedBody.get()).isEmpty();
    }

    @Test
    @DisplayName("an oversized response body is truncated to the configured limit")
    void truncatesSnippet() {
        DispatchOutcome outcome = dispatcher.dispatch(request("/huge", HttpMethodType.GET, null, 5));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.snippet()).hasSize(1_000);
    }

    // ---------------------------------------------------------------- failure classification

    @Test
    @DisplayName("4xx is a permanent failure — retrying an invalid request cannot help")
    void clientErrorsAreNotRetryable() {
        for (String path : new String[]{"/bad-request", "/not-found"}) {
            DispatchOutcome outcome = dispatcher.dispatch(request(path, HttpMethodType.POST, "{}", 5));

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.retryable()).as(path).isFalse();
            assertThat(outcome.errorMessage()).contains("not retryable");
        }
    }

    @Test
    @DisplayName("429 is treated as permanent, matching the documented 4xx rule")
    void rateLimitFollowsTheFourXxRule() {
        // Called out explicitly because it is the one 4xx that is genuinely transient. See the
        // note on HttpDispatcher.classify — honouring Retry-After belongs with M5's backoff.
        DispatchOutcome outcome = dispatcher.dispatch(request("/rate-limited", HttpMethodType.POST, "{}", 5));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.retryable()).isFalse();
        assertThat(outcome.responseCode()).isEqualTo(429);
    }

    @Test
    @DisplayName("5xx is retryable and keeps the status code and body")
    void serverErrorsAreRetryable() {
        DispatchOutcome outcome = dispatcher.dispatch(request("/boom", HttpMethodType.POST, "{}", 5));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.retryable()).isTrue();
        assertThat(outcome.responseCode()).isEqualTo(500);
        assertThat(outcome.snippet()).isEqualTo("kaboom");

        assertThat(dispatcher.dispatch(request("/unavailable", HttpMethodType.POST, "{}", 5))
                .retryable()).isTrue();
    }

    @Test
    @DisplayName("a read timeout is retryable and has no status code")
    void timeoutIsRetryable() {
        // Server sleeps 3s; the job allows 1s.
        DispatchOutcome outcome = dispatcher.dispatch(request("/slow", HttpMethodType.GET, null, 1));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.retryable()).isTrue();
        assertThat(outcome.responseCode()).isNull();
        assertThat(outcome.errorMessage()).isNotBlank();
    }

    @Test
    @DisplayName("a connection failure is retryable and names the cause")
    void connectionFailureIsRetryable() {
        // Port 1 on loopback: nothing is listening, so the connection is refused outright.
        DispatchOutcome outcome = dispatcher.dispatch(new HttpDispatcher.DispatchRequest(
                7L, "http://127.0.0.1:1/nowhere", HttpMethodType.POST, Map.of(), "{}", 5));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.retryable()).isTrue();
        assertThat(outcome.responseCode()).isNull();
        assertThat(outcome.errorMessage()).isNotBlank();
    }
}
