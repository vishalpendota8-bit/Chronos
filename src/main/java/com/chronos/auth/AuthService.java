package com.chronos.auth;

import com.chronos.auth.dto.LoginRequest;
import com.chronos.auth.dto.RegisterRequest;
import com.chronos.auth.dto.TokenResponse;
import com.chronos.auth.dto.UserResponse;
import com.chronos.common.ConflictException;
import com.chronos.security.ChronosUserDetails;
import com.chronos.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registration and login. */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthService(UserRepository users,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    /**
     * Creates a USER and logs them straight in.
     *
     * <p><b>The role is not taken from the request.</b> It is hard-coded, because anything the
     * client can influence, the client can abuse — a {@code "role":"ADMIN"} field would be a
     * one-line privilege escalation. Admins are created only by an existing admin
     * ({@link UserService#updateRole}) or by the startup bootstrap.
     */
    @Transactional
    public TokenResponse register(RegisterRequest request) {
        String email = request.email().trim();

        // Checked up front for a friendly 409. It is NOT the real guarantee: two concurrent
        // registrations can both pass this check before either commits. The LOWER(email) unique
        // index in V1__init.sql is the actual guard, and the catch below turns its violation
        // into the same 409 rather than a 500.
        if (users.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("An account with email " + email + " already exists");
        }

        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(Role.USER)
                .build();

        try {
            user = users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("An account with email " + email + " already exists");
        }

        log.info("Registered user {} (id={})", user.getEmail(), user.getId());
        return issueFor(user);
    }

    /**
     * Verifies credentials and returns a token.
     *
     * <p>The password comparison happens inside {@code AuthenticationManager} →
     * {@code DaoAuthenticationProvider}, which loads the user via
     * {@link com.chronos.security.ChronosUserDetailsService} and calls
     * {@code passwordEncoder.matches}. A failure throws {@code AuthenticationException}, which
     * {@link com.chronos.common.GlobalExceptionHandler} turns into a deliberately vague 401.
     */
    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email().trim(), request.password()));

        // The provider hands back the principal our UserDetailsService produced, so we already
        // have the id and role — no second query to build the token.
        ChronosUserDetails principal = (ChronosUserDetails) authentication.getPrincipal();

        User user = users.findById(principal.id()).orElseThrow(() -> new IllegalStateException(
                "Authenticated user " + principal.id() + " vanished between load and token issue"));

        log.debug("Login succeeded for {}", user.getEmail());
        return issueFor(user);
    }

    /** Re-reads the user so /auth/me reflects a role change made since the token was issued. */
    @Transactional(readOnly = true)
    public UserResponse currentUser(Long userId) {
        return users.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new IllegalStateException("Authenticated user " + userId + " no longer exists"));
    }

    private TokenResponse issueFor(User user) {
        return TokenResponse.bearer(
                jwtService.issue(user),
                jwtService.expirySeconds(),
                UserResponse.from(user));
    }
}
