package com.chronos.job;

import com.chronos.common.BadRequestException;
import com.chronos.config.JobProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests — no Spring, no Docker. Runs under `mvn test`. */
class JobValidatorTest {

    private final JobValidator validator = new JobValidator(
            new JobProperties(false, 20, 65_536), new ObjectMapper());

    /** Same validator with the SSRF guard switched on. */
    private final JobValidator strict = new JobValidator(
            new JobProperties(true, 20, 65_536), new ObjectMapper());

    // ---------------------------------------------------------------- URLs

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/hook",
            "http://example.com:8080/a/b?c=d",
            "https://example.com"
    })
    @DisplayName("ordinary http(s) URLs are accepted")
    void acceptsHttpUrls(String url) {
        assertThatCode(() -> validator.validateTargetUrl(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "file:///etc/passwd",       // local file read
            "ftp://example.com/x",      // unsupported protocol
            "jar:file:///x!/y"          // nested scheme
    })
    @DisplayName("non-http schemes are rejected — this is what stops file:// exfiltration")
    void rejectsForeignSchemes(String url) {
        assertThatThrownBy(() -> validator.validateTargetUrl(url))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("a relative URL is rejected")
    void rejectsRelativeUrl() {
        assertThatThrownBy(() -> validator.validateTargetUrl("/just/a/path"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("absolute");
    }

    @Test
    @DisplayName("with the guard enabled, loopback targets are refused")
    void rejectsLoopbackWhenStrict() {
        assertThatThrownBy(() -> strict.validateTargetUrl("http://127.0.0.1:8080/internal"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("private or loopback");
    }

    @Test
    @DisplayName("with the guard disabled (the shipped default), loopback targets are allowed")
    void allowsLoopbackByDefault() {
        assertThatCode(() -> validator.validateTargetUrl("http://127.0.0.1:8080/internal"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("with the guard enabled, private ranges are refused")
    void rejectsPrivateRangeWhenStrict() {
        assertThatThrownBy(() -> strict.validateTargetUrl("http://10.0.0.5/admin"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> strict.validateTargetUrl("http://192.168.1.1/admin"))
                .isInstanceOf(BadRequestException.class);
        // The AWS/GCP metadata endpoint — the single most abused SSRF target.
        assertThatThrownBy(() -> strict.validateTargetUrl("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(BadRequestException.class);
    }

    // ---------------------------------------------------------------- headers

    @Test
    @DisplayName("normal headers pass")
    void acceptsHeaders() {
        assertThatCode(() -> validator.validateHeaders(Map.of("X-Tenant", "acme")))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateHeaders(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a job cannot override the idempotency key Chronos guarantees")
    void rejectsReservedHeader() {
        assertThatThrownBy(() -> validator.validateHeaders(Map.of("x-idempotency-key", "mine")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("cannot be overridden");

        // Header names are case-insensitive, so the check must be too.
        assertThatThrownBy(() -> validator.validateHeaders(Map.of("Content-Length", "0")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("CRLF in a header value is rejected — header injection")
    void rejectsHeaderInjection() {
        Map<String, String> evil = new LinkedHashMap<>();
        evil.put("X-Note", "ok\r\nX-Admin: true");

        assertThatThrownBy(() -> validator.validateHeaders(evil))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("line breaks");
    }

    @Test
    @DisplayName("too many headers is rejected")
    void rejectsTooManyHeaders() {
        Map<String, String> many = new LinkedHashMap<>();
        for (int i = 0; i < 21; i++) {
            many.put("X-H" + i, "v");
        }

        assertThatThrownBy(() -> validator.validateHeaders(many))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("at most 20");
    }

    // ---------------------------------------------------------------- payload

    @Test
    @DisplayName("valid JSON payloads pass; malformed ones fail before reaching jsonb")
    void validatesPayloadJson() {
        assertThatCode(() -> validator.validatePayload("{\"a\":1}", HttpMethodType.POST))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validatePayload(null, HttpMethodType.POST))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validatePayload("{not json", HttpMethodType.POST))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("valid JSON");
    }

    @Test
    @DisplayName("a body on GET or DELETE is rejected")
    void rejectsBodyOnBodylessMethods() {
        assertThatThrownBy(() -> validator.validatePayload("{\"a\":1}", HttpMethodType.GET))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("must not define a payload");

        assertThatThrownBy(() -> validator.validatePayload("{\"a\":1}", HttpMethodType.DELETE))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("an oversized payload is rejected")
    void rejectsHugePayload() {
        String huge = "{\"a\":\"" + "x".repeat(70_000) + "\"}";

        assertThatThrownBy(() -> validator.validatePayload(huge, HttpMethodType.POST))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("maximum");
    }
}
