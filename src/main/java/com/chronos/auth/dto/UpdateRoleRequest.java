package com.chronos.auth.dto;

import com.chronos.auth.Role;
import jakarta.validation.constraints.NotNull;

/** ADMIN-only role change. An unknown role name fails Jackson binding before it reaches us. */
public record UpdateRoleRequest(

        @NotNull(message = "Role is required (USER or ADMIN)")
        Role role
) {
}
