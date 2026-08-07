package com.chronos.security;

import com.chronos.auth.Role;
import com.chronos.auth.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;

/**
 * The authenticated principal.
 *
 * <p><b>Why a custom UserDetails instead of Spring's built-in {@code org.springframework
 * .security.core.userdetails.User}:</b> that one identifies a principal by username only. Every
 * ownership rule from M3 onwards is {@code job.owner_id = ?}, so the principal must carry the
 * numeric user id; otherwise every request would need an extra email→id lookup.
 *
 * @param passwordHash non-null <em>only</em> during the login exchange, where
 *        {@code DaoAuthenticationProvider} needs something to compare the submitted password
 *        against. The principal that ends up in the {@code SecurityContext} on a normal request
 *        is built from a JWT and always has {@code null} here — so a password hash can never
 *        leak out of a request-scoped principal, a log line, or a serialised context.
 */
public record ChronosUserDetails(Long id, String email, Role role, String passwordHash)
        implements org.springframework.security.core.userdetails.UserDetails {

    /** Principal for the login exchange only: carries the hash to be verified. */
    public static ChronosUserDetails forLogin(User user) {
        return new ChronosUserDetails(user.getId(), user.getEmail(), user.getRole(),
                user.getPasswordHash());
    }

    /** Same identity with credentials dropped — what we put into the security context. */
    public ChronosUserDetails withoutCredentials() {
        return new ChronosUserDetails(id, email, role, null);
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    /**
     * Spring's role checks ({@code hasRole('ADMIN')}, {@code hasRole("ADMIN")} in config) look
     * for an authority literally named {@code ROLE_ADMIN}. Adding the prefix here is what makes
     * those expressions work.
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
