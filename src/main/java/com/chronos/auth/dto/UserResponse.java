package com.chronos.auth.dto;

import com.chronos.auth.Role;
import com.chronos.auth.User;

import java.time.Instant;

/**
 * The public view of a user. Note the absence of {@code passwordHash} — this is exactly why
 * entities are not returned directly from controllers.
 */
public record UserResponse(Long id, String email, Role role, Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getRole(), user.getCreatedAt());
    }
}
