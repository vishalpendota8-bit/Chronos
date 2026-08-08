package com.chronos.job;

import com.chronos.common.BadRequestException;
import com.chronos.config.JobProperties;
import com.chronos.job.dto.JobRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The checks Bean Validation cannot express: is this URL something we are willing to call, is
 * this payload really JSON, are these headers ours to set?
 *
 * <p>Everything here throws {@link BadRequestException}, which the global advice turns into a
 * 400 with a message the user can act on.
 */
@Component
public class JobValidator {

    private static final Logger log = LoggerFactory.getLogger(JobValidator.class);

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /**
     * Headers the dispatcher owns. Letting a job set these would either break the request
     * (Host, Content-Length) or defeat a guarantee we make to the target — notably
     * {@code X-Idempotency-Key}, which M4 sets to the execution id so a receiver can
     * de-duplicate our at-least-once delivery.
     */
    private static final Set<String> RESERVED_HEADERS = Set.of(
            "host", "content-length", "transfer-encoding", "connection",
            "x-idempotency-key");

    /** Verbs where a request body is meaningless; sending one is almost always a mistake. */
    private static final Set<HttpMethodType> BODYLESS = Set.of(
            HttpMethodType.GET, HttpMethodType.DELETE);

    private final JobProperties properties;
    private final ObjectMapper objectMapper;

    public JobValidator(JobProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Runs every non-Bean-Validation check. Called on both create and update. */
    public void validate(JobRequest request) {
        validateTargetUrl(request.targetUrl());
        validateHeaders(request.headers());
        validatePayload(request.payload(), request.httpMethod());
    }

    // ------------------------------------------------------------------ URL

    void validateTargetUrl(String targetUrl) {
        URI uri;
        try {
            uri = new URI(targetUrl.trim());
        } catch (URISyntaxException e) {
            throw new BadRequestException("Target URL is not a valid URI: " + e.getReason());
        }

        if (!uri.isAbsolute() || uri.getScheme() == null) {
            throw new BadRequestException(
                    "Target URL must be absolute and include a scheme, e.g. https://example.com/hook");
        }

        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            // file:, gopher: and friends are the classic way to turn a URL fetcher into a local
            // file reader. Only the two schemes a webhook can legitimately use are allowed.
            throw new BadRequestException(
                    "Target URL scheme '" + scheme + "' is not supported (use http or https)");
        }

        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new BadRequestException("Target URL must include a host");
        }

        if (properties.blockPrivateTargets()) {
            rejectPrivateHost(uri.getHost());
        }
    }

    /**
     * <b>New concept — SSRF (server-side request forgery):</b> Chronos makes HTTP calls on
     * behalf of whoever created the job, from inside our network. A user who cannot reach the
     * cloud metadata endpoint or an internal admin panel themselves can simply ask Chronos to
     * fetch it for them. Restricting the reachable address space is the mitigation.
     *
     * <p><b>Honest limitation:</b> this resolves the hostname once, at validation time. An
     * attacker controlling DNS can answer with a public address now and a private one when M4
     * actually dispatches — the classic DNS-rebinding TOCTOU hole. Closing it properly means
     * pinning the resolved address at dispatch time or egressing through a filtering proxy;
     * neither belongs in a validator. This raises the bar, it does not seal the door.
     */
    private void rejectPrivateHost(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new BadRequestException("Target URL host '" + host + "' could not be resolved");
        }

        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isAnyLocalAddress()
                    || address.isMulticastAddress()) {
                log.warn("Rejected job target {} resolving to non-public address {}", host, address);
                throw new BadRequestException(
                        "Target URL host '" + host + "' resolves to a private or loopback address");
            }
        }
    }

    // ------------------------------------------------------------------ headers

    void validateHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }

        if (headers.size() > properties.maxHeaders()) {
            throw new BadRequestException(
                    "A job may define at most " + properties.maxHeaders() + " headers");
        }

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey();

            if (name == null || name.isBlank()) {
                throw new BadRequestException("Header names must not be blank");
            }
            if (RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                throw new BadRequestException(
                        "Header '" + name + "' is set by Chronos and cannot be overridden");
            }
            // A newline in a header value is header injection: it lets the value terminate the
            // header and start a new one. Java's HTTP client would reject it later anyway, but
            // failing here turns a confusing runtime dispatch error into a clear 400.
            if (name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0
                    || (entry.getValue() != null
                        && (entry.getValue().indexOf('\n') >= 0 || entry.getValue().indexOf('\r') >= 0))) {
                throw new BadRequestException(
                        "Header '" + name + "' must not contain line breaks");
            }
        }
    }

    // ------------------------------------------------------------------ payload

    void validatePayload(String payload, HttpMethodType method) {
        if (payload == null || payload.isBlank()) {
            return;
        }

        if (BODYLESS.contains(method)) {
            throw new BadRequestException(
                    "A " + method + " job must not define a payload");
        }

        int bytes = payload.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > properties.maxPayloadBytes()) {
            throw new BadRequestException(
                    "Payload is " + bytes + " bytes; the maximum is " + properties.maxPayloadBytes());
        }

        // The payload column is jsonb, so Postgres will reject malformed JSON at INSERT with a
        // 500-shaped error. Parsing it here converts that into an actionable 400. We only parse
        // to check validity — the original string is what gets stored and sent, byte for byte.
        try {
            objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Payload must be valid JSON: " + e.getOriginalMessage());
        }
    }
}
