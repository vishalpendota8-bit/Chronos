package com.chronos.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Reads the authenticated principal out of the security context.
 *
 * <p>Controllers should prefer the {@code @AuthenticationPrincipal ChronosUserDetails me}
 * parameter — it is explicit and trivially mockable in tests. This helper exists for service
 * code (M3's ownership rules, M5's replay auditing) that is called from places without a
 * controller signature to thread the principal through.
 *
 * <p><b>Caveat for later modules:</b> the context is held in a {@code ThreadLocal}, so the
 * poller and dispatch threads in M4 have <em>no</em> principal. Anything running on a scheduler
 * thread must be handed the user id explicitly rather than calling this.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<ChronosUserDetails> get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof ChronosUserDetails details)) {
            return Optional.empty();
        }
        return Optional.of(details);
    }

    /** For code paths that are only reachable behind an authenticated endpoint. */
    public static ChronosUserDetails require() {
        return get().orElseThrow(() -> new IllegalStateException(
                "No authenticated user in the security context"));
    }

    public static Optional<Long> id() {
        return get().map(ChronosUserDetails::id);
    }
}
