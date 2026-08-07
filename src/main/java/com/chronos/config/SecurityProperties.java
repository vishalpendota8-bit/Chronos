package com.chronos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code chronos.security.*} block.
 *
 * @param bootstrapAdmin optional first-admin credentials; see {@link AdminBootstrap}.
 */
@ConfigurationProperties(prefix = "chronos.security")
public record SecurityProperties(BootstrapAdmin bootstrapAdmin) {

    public record BootstrapAdmin(String email, String password) {

        /** Bootstrap only runs when both halves are supplied. */
        public boolean isConfigured() {
            return email != null && !email.isBlank()
                    && password != null && !password.isBlank();
        }
    }

    /** Boot binds a missing `bootstrap-admin` block to null; normalise so callers need no null check. */
    public SecurityProperties {
        if (bootstrapAdmin == null) {
            bootstrapAdmin = new BootstrapAdmin(null, null);
        }
    }
}
