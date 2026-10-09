package com.forgeflow.shared.ratelimit;

import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static com.forgeflow.support.ScriptedLlm.write;
import static com.forgeflow.support.ScriptedLlm.INDEX;
import static org.assertj.core.api.Assertions.assertThat;

/** The limiter in the real filter chain, with limits low enough to hit. */
@TestPropertySource(properties = {
        "forgeflow.ratelimit.enabled=true",
        "forgeflow.ratelimit.auth-per-minute=3",
        "forgeflow.ratelimit.ai-per-minute=2",
        "forgeflow.ratelimit.api-per-minute=1000"
})
class RateLimitTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    /** Each test gets its own client address, so per-IP buckets don't leak between tests. */
    private static String freshIp() {
        return "10.9." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);
    }

    private MockHttpServletResponse signupFrom(String ip, String email) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/signup")
                        .with(r -> { r.setRemoteAddr(ip); return r; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", "correct-horse-1", "name", "x"))))
                .andReturn().getResponse();
    }

    @Test
    void signupsFromOneAddressAreCappedWithRetryAfter() throws Exception {
        String ip = freshIp();
        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse ok = signupFrom(ip, UUID.randomUUID() + "@rl.test");
            assertThat(ok.getStatus()).isEqualTo(201);
            assertThat(ok.getHeader("X-RateLimit-Limit")).isEqualTo("3");
        }

        MockHttpServletResponse limited = signupFrom(ip, UUID.randomUUID() + "@rl.test");
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(limited.getHeader("Retry-After"))).isBetween(1L, 20L);
        JsonNode body = json.readTree(limited.getContentAsString());
        assertThat(body.path("limit").asText()).isEqualTo("auth");
        assertThat(body.path("detail").asText()).contains("try again");

        // A different address has its own allowance.
        assertThat(signupFrom(freshIp(), UUID.randomUUID() + "@rl.test").getStatus()).isEqualTo(201);
    }

    @Test
    void generationIsLimitedPerUserBeforeAnyWorkIsDone() {
        Account a = signup("burst");          // from 127.0.0.1 - this class's only signup there
        long id = createProject(a, "rl");
        for (int i = 0; i < 2; i++) {
            llm.then(calls(write("index.html", INDEX.replace("<link rel=\"stylesheet\" href=\"styles.css\">", "")
                    .replace("<script src=\"app.js\"></script>", "")))).then(calls(finish("ok")));
            post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "go"), 200);
        }
        long runsBefore = jdbc.queryForObject("SELECT count(*) FROM generation_runs WHERE project_id = ?", Long.class, id);

        JsonNode limited = post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "again"), 429);
        assertThat(limited.path("limit").asText()).isEqualTo("ai");

        // Refused at the door: no run was even started.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM generation_runs WHERE project_id = ?", Long.class, id))
                .isEqualTo(runsBefore);
        // Ordinary reads are a different, much larger bucket.
        get("/api/v1/projects/" + id + "/files", a, 200);
    }
}
