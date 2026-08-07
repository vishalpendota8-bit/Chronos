package com.chronos.security;

import com.chronos.auth.Role;
import com.chronos.auth.User;
import com.chronos.config.JwtProperties;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests — no Spring context, no database, no Docker. These run under `mvn test`.
 *
 * <p>The interesting cases here are all the negative ones: a token library that happily accepts
 * a tampered token is worse than no token library at all.
 */
class JwtServiceTest {

    private static final String SECRET = "a-test-secret-that-is-definitely-long-enough-32+";

    private final JwtService jwtService = serviceWith(SECRET, "chronos", Duration.ofHours(1));

    private static JwtService serviceWith(String secret, String issuer, Duration expiry) {
        return new JwtService(new JwtProperties(secret, issuer, expiry), new MockEnvironment());
    }

    private static User user(long id, String email, Role role) {
        return User.builder().id(id).email(email).passwordHash("irrelevant").role(role).build();
    }

    @Test
    @DisplayName("a freshly issued token parses back to the same principal")
    void roundTrip() {
        String token = jwtService.issue(user(42L, "ada@chronos.test", Role.ADMIN));

        ChronosUserDetails principal = jwtService.parse(token);

        assertThat(principal.id()).isEqualTo(42L);
        assertThat(principal.email()).isEqualTo("ada@chronos.test");
        assertThat(principal.role()).isEqualTo(Role.ADMIN);
        assertThat(principal.getAuthorities())
                .extracting("authority").containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("the parsed principal never carries a password hash")
    void parsedPrincipalHasNoCredentials() {
        String token = jwtService.issue(user(1L, "user@chronos.test", Role.USER));

        assertThat(jwtService.parse(token).getPassword()).isNull();
    }

    @Test
    @DisplayName("a token signed with a different secret is rejected")
    void foreignSignatureIsRejected() {
        String foreign = serviceWith("a-completely-different-secret-also-32-bytes+", "chronos",
                Duration.ofHours(1))
                .issue(user(1L, "mallory@chronos.test", Role.ADMIN));

        assertThatThrownBy(() -> jwtService.parse(foreign)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("editing the payload invalidates the signature")
    void tamperedPayloadIsRejected() {
        String token = jwtService.issue(user(1L, "user@chronos.test", Role.USER));

        // A JWT is header.payload.signature, all base64url. Flipping one character of the
        // payload is exactly what an attacker trying to promote themselves would do.
        String[] parts = token.split("\\.");
        char first = parts[1].charAt(0);
        parts[1] = (first == 'A' ? 'B' : 'A') + parts[1].substring(1);
        String tampered = String.join(".", parts);

        assertThatThrownBy(() -> jwtService.parse(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("a token from a different issuer is rejected even with a valid signature")
    void wrongIssuerIsRejected() {
        String otherIssuer = serviceWith(SECRET, "some-other-app", Duration.ofHours(1))
                .issue(user(1L, "user@chronos.test", Role.USER));

        assertThatThrownBy(() -> jwtService.parse(otherIssuer)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("an expired token is rejected with ExpiredJwtException")
    void expiredTokenIsRejected() {
        // Negative expiry => the token is born already expired, no waiting in the test.
        String expired = serviceWith(SECRET, "chronos", Duration.ofSeconds(-60))
                .issue(user(1L, "user@chronos.test", Role.USER));

        assertThatThrownBy(() -> jwtService.parse(expired))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    @DisplayName("garbage is rejected rather than parsed")
    void garbageIsRejected() {
        assertThatThrownBy(() -> jwtService.parse("not-a-jwt"))
                .isInstanceOfAny(JwtException.class, IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a secret shorter than 256 bits fails at construction, not at first login")
    void shortSecretIsRejectedAtStartup() {
        assertThatThrownBy(() -> serviceWith("too-short", "chronos", Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    @DisplayName("the development default secret is refused outside the dev/test profiles")
    void devSecretIsRefusedInProduction() {
        assertThatThrownBy(() -> new JwtService(
                new JwtProperties(JwtProperties.DEV_SECRET, "chronos", Duration.ofHours(1)),
                new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHRONOS_JWT_SECRET");
    }

    @Test
    @DisplayName("the development default secret is allowed when the dev profile is active")
    void devSecretIsAllowedInDev() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");

        JwtService service = new JwtService(
                new JwtProperties(JwtProperties.DEV_SECRET, "chronos", Duration.ofHours(1)), dev);

        assertThat(service.expirySeconds()).isEqualTo(3600);
    }
}
