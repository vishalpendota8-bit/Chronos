package com.chronos.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Turns {@code Authorization: Bearer <jwt>} into an authenticated {@code SecurityContext}.
 *
 * <p><b>New concept — the filter chain:</b> every request passes through an ordered list of
 * servlet filters before it ever reaches a controller. Authentication is just "some filter put
 * an {@code Authentication} into the {@code SecurityContextHolder}". This filter is registered
 * ahead of {@code UsernamePasswordAuthenticationFilter} so that by the time the authorisation
 * rules are evaluated, the principal is already there.
 *
 * <p>{@code OncePerRequestFilter} guarantees a single execution per request even when the
 * container internally forwards or asynchronously re-dispatches it — without it, a forward
 * would re-parse the token.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final RestAuthenticationEntryPoint entryPoint;

    public JwtAuthenticationFilter(JwtService jwtService, RestAuthenticationEntryPoint entryPoint) {
        this.jwtService = jwtService;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String token = bearerToken(request);

        // No token is not an error here. Public endpoints (/auth/login) must still work, and for
        // protected ones the authorisation rules below us will reject the anonymous request and
        // the entry point will produce the 401. Rejecting here would break the public routes.
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            ChronosUserDetails principal = jwtService.parse(token);

            // Credentials are null: the token *is* the credential, and it has already been
            // verified. Authorities come from the principal, so the request is now authorised.
            var authentication = new UsernamePasswordAuthenticationToken(
                    principal, null, principal.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            // Build a fresh context rather than mutating the existing one — the shared empty
            // context instance must never be written to.
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);

            filterChain.doFilter(request, response);

        } catch (ExpiredJwtException e) {
            // Called out separately because it is the one failure a well-behaved client causes
            // routinely, and it tells the UI to re-login rather than to report a bug.
            log.debug("Expired token on {}: {}", request.getRequestURI(), e.getMessage());
            entryPoint.reject(request, response, "Access token has expired");

        } catch (JwtException | IllegalArgumentException e) {
            // Bad signature, wrong issuer, malformed token, unparseable claims. Logged at debug
            // only: a public endpoint being probed with junk tokens should not fill the log.
            log.debug("Rejected token on {}: {}", request.getRequestURI(), e.getMessage());
            entryPoint.reject(request, response, "Invalid access token");
        }
    }

    /**
     * We never clear the context here. Spring Security's {@code SecurityContextHolderFilter}
     * sits above us and clears it when the request completes — important, because the servlet
     * container reuses threads and a leaked principal would be visible to the next request.
     */
    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
