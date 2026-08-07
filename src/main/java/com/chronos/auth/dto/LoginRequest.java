package com.chronos.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Login payload.
 *
 * <p>Deliberately looser validation than {@link RegisterRequest}: rejecting a login because the
 * password is 6 characters would tell an attacker that the password rules changed, and would
 * lock out users whose password predates a rule change. Only "not blank" is checked; the
 * credential itself is either right or wrong.
 */
public record LoginRequest(

        @NotBlank(message = "Email is required")
        String email,

        @NotBlank(message = "Password is required")
        String password
) {
}
