package com.chronos.job;

import com.chronos.auth.Role;
import com.chronos.auth.User;
import com.chronos.auth.UserRepository;
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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Job CRUD, validation and — most importantly — the ownership rules, exercised end to end
 * through the real filter chain and Postgres.
 *
 * <p>Requires Docker; runs under `mvn verify`.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JobIT extends PostgresTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final MockMvc mvc;
    private final ObjectMapper json;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    JobIT(MockMvc mvc, ObjectMapper json, UserRepository users, PasswordEncoder passwordEncoder) {
        this.mvc = mvc;
        this.json = json;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    // ---------------------------------------------------------------- fixtures

    private static String unique(String prefix) {
        return prefix + "-" + SEQ.incrementAndGet();
    }

    /** Registers a fresh USER and returns their bearer token. */
    private String userToken() throws Exception {
        String email = unique("jobuser") + "@chronos.test";
        MvcResult result = mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("email", email, "password", "correct-horse-battery"))))
                .andExpect(status().isCreated())
                .andReturn();
        return "Bearer " + node(result).get("accessToken").asText();
    }

    private String adminToken() throws Exception {
        String email = unique("jobadmin") + "@chronos.test";
        users.saveAndFlush(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("admin-password-1"))
                .role(Role.ADMIN)
                .build());

        MvcResult result = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("email", email, "password", "admin-password-1"))))
                .andExpect(status().isOk())
                .andReturn();
        return "Bearer " + node(result).get("accessToken").asText();
    }

    private JsonNode node(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** A minimal valid job body; callers override individual fields. */
    private Map<String, Object> jobBody(String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("targetUrl", "https://example.test/hook");
        body.put("httpMethod", "POST");
        body.put("cronExpr", "0 2 * * *");
        return body;
    }

    private JsonNode createJob(String token, Map<String, Object> body) throws Exception {
        MvcResult result = mvc.perform(post("/jobs")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return node(result);
    }

    // ---------------------------------------------------------------- create

    @Test
    @DisplayName("creating a job applies the configured defaults and computes a next run")
    void createAppliesDefaults() throws Exception {
        String token = userToken();
        String name = unique("nightly");

        mvc.perform(post("/jobs")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(jobBody(name))))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.status").value("ENABLED"))
                .andExpect(jsonPath("$.timezone").value("UTC"))
                .andExpect(jsonPath("$.maxAttempts").value(3))
                .andExpect(jsonPath("$.initialBackoffSec").value(10))
                .andExpect(jsonPath("$.timeoutSec").value(30))
                .andExpect(jsonPath("$.nextRunAt").isNotEmpty())
                .andExpect(jsonPath("$.cronDescription").isNotEmpty());
    }

    @Test
    @DisplayName("explicit tuning values override the defaults")
    void createHonoursExplicitSettings() throws Exception {
        String token = userToken();
        Map<String, Object> body = jobBody(unique("tuned"));
        body.put("timezone", "Europe/Berlin");
        body.put("maxAttempts", 7);
        body.put("initialBackoffSec", 30);
        body.put("backoffMultiplier", 1.5);
        body.put("timeoutSec", 120);

        createJob(token, body);

        mvc.perform(get("/jobs").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].timezone").value("Europe/Berlin"))
                .andExpect(jsonPath("$.content[0].maxAttempts").value(7))
                .andExpect(jsonPath("$.content[0].timeoutSec").value(120));
    }

    @Test
    @DisplayName("a client cannot set status or nextRunAt directly")
    void createIgnoresServerControlledFields() throws Exception {
        String token = userToken();
        Map<String, Object> body = jobBody(unique("sneaky"));
        body.put("status", "ARCHIVED");
        body.put("nextRunAt", "2099-01-01T00:00:00Z");

        JsonNode created = createJob(token, body);

        assertThat(created.get("status").asText()).isEqualTo("ENABLED");
        assertThat(created.get("nextRunAt").asText()).doesNotStartWith("2099");
    }

    @Test
    @DisplayName("an invalid cron expression is a 400, not a 500")
    void rejectsInvalidCron() throws Exception {
        Map<String, Object> body = jobBody(unique("badcron"));
        body.put("cronExpr", "not a cron");

        mvc.perform(post("/jobs")
                        .header("Authorization", userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("cron")));
    }

    @Test
    @DisplayName("an unknown timezone is a 400")
    void rejectsUnknownTimezone() throws Exception {
        Map<String, Object> body = jobBody(unique("badtz"));
        body.put("timezone", "Mars/Olympus_Mons");

        mvc.perform(post("/jobs")
                        .header("Authorization", userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("validator rules reach the API: bad scheme, reserved header, malformed payload")
    void rejectsInvalidTargets() throws Exception {
        String token = userToken();

        Map<String, Object> badScheme = jobBody(unique("scheme"));
        badScheme.put("targetUrl", "file:///etc/passwd");
        mvc.perform(post("/jobs").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(badScheme)))
                .andExpect(status().isBadRequest());

        Map<String, Object> reserved = jobBody(unique("reserved"));
        reserved.put("headers", Map.of("X-Idempotency-Key", "mine"));
        mvc.perform(post("/jobs").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(reserved)))
                .andExpect(status().isBadRequest());

        Map<String, Object> badPayload = jobBody(unique("payload"));
        badPayload.put("payload", "{not json");
        mvc.perform(post("/jobs").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(badPayload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("out-of-range tuning values are caught by Bean Validation with field errors")
    void rejectsOutOfRangeSettings() throws Exception {
        Map<String, Object> body = jobBody(unique("range"));
        body.put("maxAttempts", 99);
        body.put("timeoutSec", 9999);

        mvc.perform(post("/jobs")
                        .header("Authorization", userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='maxAttempts')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='timeoutSec')]").exists());
    }

    @Test
    @DisplayName("two jobs with the same name for one owner is a 409, but different owners are fine")
    void nameIsUniquePerOwner() throws Exception {
        String tokenA = userToken();
        String tokenB = userToken();
        String name = unique("shared-name");

        createJob(tokenA, jobBody(name));

        mvc.perform(post("/jobs").header("Authorization", tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(jobBody(name))))
                .andExpect(status().isConflict());

        // A different owner may reuse the name — uniqueness is (owner_id, name).
        createJob(tokenB, jobBody(name));
    }

    // ---------------------------------------------------------------- ownership

    @Test
    @DisplayName("a user's listing contains only their own jobs")
    void listIsScopedToOwner() throws Exception {
        String tokenA = userToken();
        String tokenB = userToken();

        JsonNode mine = createJob(tokenA, jobBody(unique("mine")));
        createJob(tokenB, jobBody(unique("theirs")));

        MvcResult result = mvc.perform(get("/jobs").header("Authorization", tokenA))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode content = node(result).get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("id").asLong()).isEqualTo(mine.get("id").asLong());
    }

    @Test
    @DisplayName("another user's job is 404, not 403 — the id must not be confirmed")
    void otherUsersJobIsInvisible() throws Exception {
        String tokenA = userToken();
        String tokenB = userToken();
        long id = createJob(tokenA, jobBody(unique("private"))).get("id").asLong();

        mvc.perform(get("/jobs/" + id).header("Authorization", tokenB))
                .andExpect(status().isNotFound());

        mvc.perform(put("/jobs/" + id).header("Authorization", tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(jobBody(unique("hijack")))))
                .andExpect(status().isNotFound());

        mvc.perform(post("/jobs/" + id + "/pause").header("Authorization", tokenB))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/jobs/" + id).header("Authorization", tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an admin can read and modify any user's job")
    void adminSeesEverything() throws Exception {
        String owner = userToken();
        String admin = adminToken();
        JsonNode job = createJob(owner, jobBody(unique("adminview")));
        long id = job.get("id").asLong();

        mvc.perform(get("/jobs/" + id).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(job.get("ownerId").asLong()));

        mvc.perform(post("/jobs/" + id + "/pause").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));

        // The admin listing spans owners, so it must see at least this other user's job.
        mvc.perform(get("/jobs?size=200").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id==" + id + ")]").exists());
    }

    @Test
    @DisplayName("every /jobs route requires a token")
    void jobsRequireAuthentication() throws Exception {
        mvc.perform(get("/jobs")).andExpect(status().isUnauthorized());
        mvc.perform(post("/jobs").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/jobs/cron/preview").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- update

    @Test
    @DisplayName("editing an unrelated field leaves the schedule alone; changing cron moves it")
    void nextRunOnlyMovesWhenTheScheduleChanges() throws Exception {
        String token = userToken();
        String name = unique("schedule");
        JsonNode created = createJob(token, jobBody(name));
        String originalNextRun = created.get("nextRunAt").asText();

        // Same cron, different timeout — the next run must not drift.
        Map<String, Object> sameSchedule = jobBody(name);
        sameSchedule.put("timeoutSec", 90);
        MvcResult unchanged = mvc.perform(put("/jobs/" + created.get("id").asLong())
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(sameSchedule)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeoutSec").value(90))
                .andReturn();
        assertThat(node(unchanged).get("nextRunAt").asText()).isEqualTo(originalNextRun);

        // Different cron — now it must be recomputed.
        Map<String, Object> newSchedule = jobBody(name);
        newSchedule.put("cronExpr", "0 3 * * *");
        MvcResult moved = mvc.perform(put("/jobs/" + created.get("id").asLong())
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(newSchedule)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(node(moved).get("nextRunAt").asText()).isNotEqualTo(originalNextRun);
    }

    @Test
    @DisplayName("renaming onto another of your own jobs' names is a 409")
    void renameCollisionIsConflict() throws Exception {
        String token = userToken();
        String firstName = unique("first");
        createJob(token, jobBody(firstName));
        JsonNode second = createJob(token, jobBody(unique("second")));

        Map<String, Object> rename = jobBody(firstName);
        mvc.perform(put("/jobs/" + second.get("id").asLong())
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(rename)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("keeping your own name on update is allowed (the row must not collide with itself)")
    void renameToSameNameIsAllowed() throws Exception {
        String token = userToken();
        String name = unique("keepname");
        JsonNode created = createJob(token, jobBody(name));

        Map<String, Object> body = jobBody(name);
        body.put("targetUrl", "https://example.test/other");
        mvc.perform(put("/jobs/" + created.get("id").asLong())
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetUrl").value("https://example.test/other"));
    }

    // ---------------------------------------------------------------- state transitions

    @Test
    @DisplayName("pause clears the next run, resume recomputes it, and both are idempotent")
    void pauseAndResume() throws Exception {
        String token = userToken();
        long id = createJob(token, jobBody(unique("toggle"))).get("id").asLong();

        mvc.perform(post("/jobs/" + id + "/pause").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.nextRunAt").doesNotExist());

        // Pausing twice is a no-op rather than an error.
        mvc.perform(post("/jobs/" + id + "/pause").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));

        mvc.perform(post("/jobs/" + id + "/resume").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENABLED"))
                .andExpect(jsonPath("$.nextRunAt").isNotEmpty());

        mvc.perform(post("/jobs/" + id + "/resume").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENABLED"));
    }

    @Test
    @DisplayName("delete archives: hidden by default, visible with includeArchived, history kept")
    void deleteArchives() throws Exception {
        String token = userToken();
        String name = unique("archiveme");
        long id = createJob(token, jobBody(name)).get("id").asLong();

        mvc.perform(delete("/jobs/" + id).header("Authorization", token))
                .andExpect(status().isNoContent());

        mvc.perform(get("/jobs").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id==" + id + ")]").doesNotExist());

        mvc.perform(get("/jobs?includeArchived=true").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id==" + id + ")]").exists());

        // The row still exists and is still fetchable by id.
        mvc.perform(get("/jobs/" + id).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.nextRunAt").doesNotExist());

        // Deleting again is a no-op, not a 404.
        mvc.perform(delete("/jobs/" + id).header("Authorization", token))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("an archived job cannot be updated, paused or resumed")
    void archivedJobIsFrozen() throws Exception {
        String token = userToken();
        String name = unique("frozen");
        long id = createJob(token, jobBody(name)).get("id").asLong();
        mvc.perform(delete("/jobs/" + id).header("Authorization", token))
                .andExpect(status().isNoContent());

        mvc.perform(put("/jobs/" + id).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(jobBody(name))))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/jobs/" + id + "/pause").header("Authorization", token))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/jobs/" + id + "/resume").header("Authorization", token))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- cron preview

    @Test
    @DisplayName("cron preview returns a description and the next occurrences, without saving")
    void cronPreview() throws Exception {
        mvc.perform(post("/jobs/cron/preview")
                        .header("Authorization", userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "cronExpr", "0 2 * * *",
                                "timezone", "Europe/Berlin",
                                "count", 3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Europe/Berlin"))
                .andExpect(jsonPath("$.description").isNotEmpty())
                .andExpect(jsonPath("$.nextRuns.length()").value(3));
    }

    @Test
    @DisplayName("cron preview reports a bad expression as 400")
    void cronPreviewRejectsGarbage() throws Exception {
        mvc.perform(post("/jobs/cron/preview")
                        .header("Authorization", userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("cronExpr", "77 99 * * *"))))
                .andExpect(status().isBadRequest());
    }
}
