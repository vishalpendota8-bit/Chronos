package com.chronos.auth;

import com.chronos.auth.dto.LoginRequest;
import com.chronos.auth.dto.RegisterRequest;
import com.chronos.auth.dto.TokenResponse;
import com.chronos.auth.dto.UserResponse;
import com.chronos.security.ChronosUserDetails;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public authentication endpoints.
 *
 * <p>{@code /auth/register} and {@code /auth/login} are the only routes permitted without a
 * token; see {@link com.chronos.config.SecurityConfig}. {@code /auth/me} requires one.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 201: a user was created. The body is a token, so the client is logged in immediately. */
    @PostMapping("/register")
    public ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /**
     * Who the current token says you are.
     *
     * <p>{@code @AuthenticationPrincipal} injects whatever
     * {@link com.chronos.security.JwtAuthenticationFilter} put in the security context. This is
     * the pattern every protected controller in M3+ will use to scope data to its owner.
     */
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal ChronosUserDetails principal) {
        return authService.currentUser(principal.id());
    }
}
