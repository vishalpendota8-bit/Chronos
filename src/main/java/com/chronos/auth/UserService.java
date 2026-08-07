package com.chronos.auth;

import com.chronos.auth.dto.UserResponse;
import com.chronos.common.BadRequestException;
import com.chronos.common.NotFoundException;
import com.chronos.common.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ADMIN-only user administration. */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> list(Pageable pageable) {
        return PageResponse.from(users.findAll(pageable), UserResponse::from);
    }

    @Transactional(readOnly = true)
    public UserResponse get(Long id) {
        return users.findById(id).map(UserResponse::from)
                .orElseThrow(() -> NotFoundException.of("User", id));
    }

    /**
     * Promotes or demotes a user.
     *
     * <p>Self-demotion is blocked: an admin removing their own ADMIN role could leave the system
     * with no admin at all and no way to create one short of editing the database. Cheap guard,
     * saves a genuinely painful recovery.
     *
     * <p><b>Reminder about staleness:</b> the role lives inside already-issued JWTs, so a
     * demoted user keeps ADMIN access until their current token expires (at most
     * {@code chronos.jwt.expiry}). Documented rather than fixed — see JwtService.
     */
    @Transactional
    public UserResponse updateRole(Long id, Role newRole, Long actingAdminId) {
        if (id.equals(actingAdminId) && newRole != Role.ADMIN) {
            throw new BadRequestException("You cannot remove your own ADMIN role");
        }

        User user = users.findById(id).orElseThrow(() -> NotFoundException.of("User", id));
        Role previous = user.getRole();
        user.setRole(newRole);
        // No explicit save(): the entity is managed inside this transaction, so JPA flushes the
        // change on commit. Calling save() would be harmless but redundant.

        log.info("Admin {} changed role of user {} from {} to {}", actingAdminId, id, previous, newRole);
        return UserResponse.from(user);
    }

    /**
     * Deletes a user and, via {@code ON DELETE CASCADE} in V1__init.sql, every job, execution
     * and dead letter they own.
     *
     * <p>Tradeoff: a hard delete, not a soft one. It is the honest behaviour for "remove this
     * account", and the cascade is already defined in the schema — but it is irreversible, and
     * any of that user's jobs currently mid-flight in the executor will fail their result write.
     * M6 revisits this if job retention becomes a requirement.
     */
    @Transactional
    public void delete(Long id, Long actingAdminId) {
        if (id.equals(actingAdminId)) {
            throw new BadRequestException("You cannot delete your own account");
        }
        if (!users.existsById(id)) {
            throw NotFoundException.of("User", id);
        }

        users.deleteById(id);
        log.warn("Admin {} deleted user {} and all of their jobs", actingAdminId, id);
    }
}
