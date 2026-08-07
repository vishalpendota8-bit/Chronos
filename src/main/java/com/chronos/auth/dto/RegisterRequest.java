package com.chronos.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registration payload. A record, not an entity — the boundary type must never be the thing
 * we persist, or a client could set fields like {@code role} by simply including them.
 *
 * <p>Note what is absent: there is no {@code role}. Self-registration always produces a USER;
 * see {@link com.chronos.auth.AuthService#register}.
 */
public record RegisterRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Must be a valid email address")
        @Size(max = 255, message = "Email must be at most 255 characters")
        String email,

        // Lower bound: long enough to matter. Upper bound: BCrypt silently ignores input past
        // 72 bytes, so accepting a 200-character password would be a lie about its strength.
        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        String password
) {
}
