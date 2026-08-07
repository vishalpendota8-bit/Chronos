package com.chronos.security;

import com.chronos.auth.Role;
import com.chronos.auth.User;
import com.chronos.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Mints and verifies the access tokens. The only class in the codebase that knows what a JWT
 * looks like.
 *
 * <p><b>New concept — why a JWT at all:</b> the scheduler is meant to run as several identical
 * nodes (M7 scales it to 3). A server-side session would have to be shared between them via
 * sticky sessions or a session store. A signed token needs neither: any node can verify any
 * token with the shared secret alone, so authentication adds no coordination between nodes.
 *
 * <p><b>The cost of that:</b> a token cannot be revoked before it expires — there is no state
 * to delete. That is why the expiry is short (1h) and why a password change cannot kick out
 * existing sessions. Accepted for M2; a token-version claim checked against the user row is
 * the standard fix if it ever matters.
 *
 * <p><b>Algorithm:</b> HS256 (symmetric HMAC). Every node holds the same secret, and every node
 * is equally trusted, so asymmetric RS256 would add key management for no gain here.
 */
@Service
public class JwtService {

    /** Custom claim names. `sub` holds the user id; these carry the rest. */
    static final String CLAIM_EMAIL = "email";
    static final String CLAIM_ROLE = "role";

    private final SecretKey key;
    private final JwtProperties properties;

    public JwtService(JwtProperties properties, Environment environment) {
        // Fail fast rather than run production on the sample secret committed to git.
        boolean devProfile = environment.matchesProfiles("dev", "test");
        if (JwtProperties.DEV_SECRET.equals(properties.secret()) && !devProfile) {
            throw new IllegalStateException(
                    "chronos.jwt.secret is still the development default. Set CHRONOS_JWT_SECRET "
                            + "to a random value of at least " + JwtProperties.MIN_SECRET_BYTES + " bytes.");
        }

        byte[] material = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (material.length < JwtProperties.MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "chronos.jwt.secret must be at least " + JwtProperties.MIN_SECRET_BYTES
                            + " bytes for HS256 (got " + material.length + ")");
        }

        this.key = Keys.hmacShaKeyFor(material);
        this.properties = properties;
    }

    /**
     * Issues an access token for a persisted user.
     *
     * <p>The role travels inside the token so authorising a request needs no database read. The
     * tradeoff is staleness: demoting an ADMIN does not take effect until their current token
     * expires.
     */
    public String issue(User user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.expiry());

        return Jwts.builder()
                .issuer(properties.issuer())
                .subject(String.valueOf(user.getId()))
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_ROLE, user.getRole().name())
                // jti: a unique id per token. Unused in M2, but it is what a future revocation
                // list would key on, and it makes tokens distinguishable in logs.
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Verifies signature, issuer and expiry, then returns the principal the token asserts.
     *
     * <p>{@code parseSignedClaims} throws on any failure — a tampered payload, a token signed
     * with a different secret, or an expired one. There is no "unverified read" path here on
     * purpose: reading claims before verifying them is the classic JWT vulnerability.
     *
     * @throws JwtException if the token is missing, malformed, expired, or not ours.
     */
    public ChronosUserDetails parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.issuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        Long userId = Long.valueOf(claims.getSubject());
        String email = claims.get(CLAIM_EMAIL, String.class);
        Role role = Role.valueOf(claims.get(CLAIM_ROLE, String.class));

        // No password hash: a token-derived principal never needs (or should hold) credentials.
        return new ChronosUserDetails(userId, email, role, null);
    }

    /** Exposed so /auth/login can tell the client when to re-authenticate. */
    public long expirySeconds() {
        return properties.expiry().toSeconds();
    }
}
