package com.chronos.config;

import com.chronos.security.JwtAuthenticationFilter;
import com.chronos.security.RestAccessDeniedHandler;
import com.chronos.security.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * The security filter chain: who may call what, and how a request proves who it is.
 *
 * <p>Everything here is deliberately stateless. See {@link com.chronos.security.JwtService} for
 * why that matters to a multi-node scheduler.
 */
@Configuration
@EnableWebSecurity
// Turns on @PreAuthorize/@PostAuthorize. Not used yet — M3's ownership rules need it, and
// enabling it now means those annotations work the moment they are written rather than
// silently doing nothing (a genuinely nasty failure mode: the code looks secured but is not).
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * BCrypt at the default strength of 10 (~50ms per hash on typical hardware).
     *
     * <p><b>Why a deliberately slow hash:</b> the whole point is that an attacker holding a
     * stolen {@code users} table cannot try billions of guesses per second. Never replace this
     * with SHA-256 or similar — fast hashes are the wrong tool for passwords.
     *
     * <p>Tradeoff: strength 10 rather than 12. Login is a foreground request and 10 keeps it
     * comfortably sub-100ms; raise it later by changing this one line, since BCrypt stores the
     * cost inside the hash and old hashes keep verifying.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Boot builds this from our {@code ChronosUserDetailsService} + the encoder above, wiring a
     * {@code DaoAuthenticationProvider} behind the scenes. Only /auth/login uses it.
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthenticationFilter jwtAuthenticationFilter,
                                           RestAuthenticationEntryPoint authenticationEntryPoint,
                                           RestAccessDeniedHandler accessDeniedHandler)
            throws Exception {

        http
                // CSRF protects against a browser silently attaching an *ambient* credential
                // (a cookie) to a cross-site request. Our credential is an Authorization header
                // the client must add explicitly, so there is nothing to forge. Safe to disable
                // here; it would NOT be if we ever moved the token into a cookie.
                .csrf(csrf -> csrf.disable())

                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // No HttpSession, ever: nothing to replicate between scheduler nodes.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Turn off the servlet-container login/logout machinery we are replacing.
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())

                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)   // 401
                        .accessDeniedHandler(accessDeniedHandler))            // 403

                .authorizeHttpRequests(auth -> auth
                        // Preflight requests carry no credentials by definition.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        .requestMatchers(HttpMethod.POST, "/auth/register", "/auth/login").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()

                        // User administration is ADMIN-only, enforced at the URL level so a
                        // forgotten @PreAuthorize on a new method cannot open a hole.
                        .requestMatchers("/users", "/users/**").hasRole("ADMIN")

                        // Default deny. Anything added in M3+ is protected until proven otherwise.
                        .anyRequest().authenticated())

                // Our filter must run before the form-login filter's slot so the principal is in
                // place by the time authorizeHttpRequests is evaluated.
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * CORS for the Vite dev server that arrives in M8.
     *
     * <p>Tradeoff: the allowed origins are hard-coded to localhost dev ports. That is the safe
     * default — a wildcard would let any website on the internet call this API with a victim's
     * token. Production deployments should override this with their real origin.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:5173", "http://localhost:3000"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
