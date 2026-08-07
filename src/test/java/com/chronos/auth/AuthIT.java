package com.chronos.auth;

import com.chronos.support.PostgresTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end auth tests through the real filter chain, controllers and Postgres.
 *
 * <p>Requires Docker; runs under `mvn verify` (failsafe), not `mvn test`.
 *
 * <p>The `test` profile is what allows the development JWT secret here — see JwtService.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIT extends PostgresTestBase {

    /** Emails must be unique across the whole shared test database, not just this class. */
    private static final AtomicInteger SEQ = new AtomicInteger();

    private final MockMvc mvc;
    private final ObjectMapper json;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    AuthIT(MockMvc mvc, ObjectMapper json, UserRepository users, PasswordEncoder passwordEncoder) {
        this.mvc = mvc;
        this.json = json;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    private static String uniqueEmail(String prefix) {
        return prefix + SEQ.incrementAndGet() + "@chronos.test";
    }

    private String body(String email, String password) throws Exception {
        return json.writeValueAsString(Map.of("email", email, "password", password));
    }

    /** Registers a USER and returns their access token. */
    private String registerAndGetToken(String email) throws Exception {
        MvcResult result = mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, "correct-horse-battery")))
                .andExpect(status().isCreated())
                .andReturn();

        return tokenOf(result);
    }

    /** Creates an ADMIN directly (registration can only make USERs) and logs in as them. */
    private String adminToken(String email) throws Exception {
        users.saveAndFlush(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("admin-password-1"))
                .role(Role.ADMIN)
                .build());

        MvcResult result = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, "admin-password-1")))
                .andExpect(status().isOk())
                .andReturn();

        return tokenOf(result);
    }

    private String tokenOf(MvcResult result) throws Exception {
        JsonNode node = json.readTree(result.getResponse().getContentAsString());
        return node.get("accessToken").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    // ---------------------------------------------------------------- registration

    @Test
    @DisplayName("registration returns 201 with a bearer token and a USER role")
    void registerSucceeds() throws Exception {
        String email = uniqueEmail("register");

        mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, "correct-horse-battery")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(3600))
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.role").value("USER"))
                // The response must never expose the hash under any name.
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("the stored password is a BCrypt hash, never the plaintext")
    void passwordIsHashed() throws Exception {
        String email = uniqueEmail("hashed");
        registerAndGetToken(email);

        User stored = users.findByEmailIgnoreCase(email).orElseThrow();

        assertThat(stored.getPasswordHash()).isNotEqualTo("correct-horse-battery");
        assertThat(stored.getPasswordHash()).startsWith("$2a$");
        assertThat(passwordEncoder.matches("correct-horse-battery", stored.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("a client cannot grant itself ADMIN by adding a role field")
    void roleFromRequestIsIgnored() throws Exception {
        String email = uniqueEmail("escalate");

        mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-battery\","
                                + "\"role\":\"ADMIN\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.role").value("USER"));
    }

    @Test
    @DisplayName("a duplicate email is rejected with 409, case-insensitively")
    void duplicateEmailIsConflict() throws Exception {
        String email = uniqueEmail("dupe");
        registerAndGetToken(email);

        mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email.toUpperCase(), "another-password-9")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("invalid input returns 400 listing every offending field")
    void validationErrorsAreListed() throws Exception {
        mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("not-an-email", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='email')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='password')]").exists());
    }

    // ---------------------------------------------------------------- login

    @Test
    @DisplayName("login succeeds with the right password and is case-insensitive on email")
    void loginSucceeds() throws Exception {
        String email = uniqueEmail("login");
        registerAndGetToken(email);

        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email.toUpperCase(), "correct-horse-battery")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(email));
    }

    @Test
    @DisplayName("a wrong password and an unknown email give the same opaque 401")
    void loginFailuresAreIndistinguishable() throws Exception {
        String email = uniqueEmail("wrongpass");
        registerAndGetToken(email);

        String wrongPassword = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, "not-the-password")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String unknownUser = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(uniqueEmail("ghost"), "not-the-password")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // Same message for both — this is what stops email enumeration.
        assertThat(json.readTree(wrongPassword).get("message").asText())
                .isEqualTo(json.readTree(unknownUser).get("message").asText())
                .isEqualTo("Invalid email or password");
    }

    // ---------------------------------------------------------------- token handling

    @Test
    @DisplayName("/auth/me needs a token and returns the caller")
    void meRequiresAToken() throws Exception {
        String email = uniqueEmail("me");
        String token = registerAndGetToken(email);

        mvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication required"));

        mvc.perform(get("/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    @DisplayName("a malformed or forged token is rejected with 401, not 500")
    void invalidTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/auth/me").header("Authorization", bearer("garbage.token.here")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid access token"));
    }

    @Test
    @DisplayName("an Authorization header without the Bearer prefix is treated as no token")
    void nonBearerHeaderIsIgnored() throws Exception {
        String token = registerAndGetToken(uniqueEmail("prefix"));

        mvc.perform(get("/auth/me").header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- authorisation

    @Test
    @DisplayName("a USER gets 403 on the admin routes, an ADMIN gets 200")
    void adminRoutesAreRoleGated() throws Exception {
        String userToken = registerAndGetToken(uniqueEmail("plainuser"));
        String adminToken = adminToken(uniqueEmail("admin"));

        mvc.perform(get("/users").header("Authorization", bearer(userToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        mvc.perform(get("/users").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").isNumber());
    }

    @Test
    @DisplayName("an admin can promote a user, and the change shows up on /auth/me")
    void adminCanPromote() throws Exception {
        String email = uniqueEmail("promoteme");
        String userToken = registerAndGetToken(email);
        String admin = adminToken(uniqueEmail("promoter"));
        Long userId = users.findByEmailIgnoreCase(email).orElseThrow().getId();

        mvc.perform(patch("/users/" + userId + "/role")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        // /auth/me re-reads the database, so it reflects the promotion immediately even though
        // the caller's token still carries the old role.
        mvc.perform(get("/auth/me").header("Authorization", bearer(userToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    @DisplayName("an admin cannot demote or delete themselves")
    void adminCannotLockThemselvesOut() throws Exception {
        String email = uniqueEmail("selfadmin");
        String token = adminToken(email);
        Long id = users.findByEmailIgnoreCase(email).orElseThrow().getId();

        mvc.perform(patch("/users/" + id + "/role")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(delete("/users/" + id).header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an admin deleting another user gets 204, and 404 for an unknown id")
    void adminCanDeleteOthers() throws Exception {
        String victim = uniqueEmail("victim");
        registerAndGetToken(victim);
        Long victimId = users.findByEmailIgnoreCase(victim).orElseThrow().getId();
        String admin = adminToken(uniqueEmail("deleter"));

        mvc.perform(delete("/users/" + victimId).header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());

        assertThat(users.findByEmailIgnoreCase(victim)).isEmpty();

        mvc.perform(delete("/users/" + victimId).header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound());
    }
}
