package com.chronos.security;

import com.chronos.auth.UserRepository;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads a user by email for the login exchange.
 *
 * <p>Used by exactly one thing: {@code DaoAuthenticationProvider} during {@code POST
 * /auth/login}. Normal requests never come through here — they carry a JWT, and
 * {@link JwtService#parse} builds the principal from the token without touching the database.
 * That is the point of the token: authorising a request costs zero queries.
 *
 * <p>Defining this bean also stops Boot from auto-configuring its fallback in-memory user and
 * printing a generated password at startup.
 */
@Service
public class ChronosUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public ChronosUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public ChronosUserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return users.findByEmailIgnoreCase(email)
                .map(ChronosUserDetails::forLogin)
                // DaoAuthenticationProvider converts this into BadCredentialsException (it also
                // runs a dummy hash comparison first) so "no such user" and "wrong password"
                // take the same time and return the same message. That defeats both email
                // enumeration and timing attacks — do not "improve" the message here.
                .orElseThrow(() -> new UsernameNotFoundException("No user with email " + email));
    }
}
