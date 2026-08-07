package com.chronos.config;

import com.chronos.auth.Role;
import com.chronos.auth.User;
import com.chronos.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first ADMIN at startup, if configured.
 *
 * <p><b>Why this is needed:</b> {@code /auth/register} always produces a USER, and only an
 * ADMIN can promote anyone. Without a bootstrap there is no path to the first admin except
 * hand-editing the database.
 *
 * <p>Tradeoff: the password comes from configuration, which means it may sit in an environment
 * variable or a compose file. That is why this runs only when explicitly configured, does
 * nothing if the account already exists (so the variable can be unset after first boot), and
 * never updates an existing user's password.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final SecurityProperties properties;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrap(SecurityProperties properties, UserRepository users,
                          PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        SecurityProperties.BootstrapAdmin admin = properties.bootstrapAdmin();
        if (!admin.isConfigured()) {
            return;
        }

        String email = admin.email().trim();
        if (users.existsByEmailIgnoreCase(email)) {
            log.debug("Bootstrap admin {} already exists — nothing to do", email);
            return;
        }

        try {
            users.saveAndFlush(User.builder()
                    .email(email)
                    .passwordHash(passwordEncoder.encode(admin.password()))
                    .role(Role.ADMIN)
                    .build());
            log.info("Created bootstrap ADMIN account {}", email);
        } catch (DataIntegrityViolationException e) {
            // Expected when several scheduler nodes boot at once (M7 runs three): they all see
            // "no admin", all try to insert, and the unique index lets exactly one win. The
            // losers land here, and the outcome is still correct — the admin exists.
            log.info("Bootstrap admin {} was created concurrently by another node", email);
        }
    }
}
