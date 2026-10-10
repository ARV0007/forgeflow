package com.forgeflow.account;

import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static com.forgeflow.support.ScriptedLlm.INDEX;
import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static com.forgeflow.support.ScriptedLlm.write;
import static org.assertj.core.api.Assertions.assertThat;

/** Personal access tokens: shown once, stored hashed, revocable, and MCP acts as their owner. */
class ApiTokenTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    private static final String TOKENS = "/api/v1/me/tokens";

    private Account withToken(Account a, String token) {
        return new Account(a.id(), a.email(), token);
    }

    private JsonNode mcp(String token, String tool, Map<String, Object> args) {
        return post("/mcp", new Account(null, null, token),
                Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", Map.of("name", tool, "arguments", args)),
                200).path("result");
    }

    @Test
    void aTokenIsShownOnceStoredHashedWorksLikeASignInAndDiesWhenRevoked() {
        Account a = signup("pat");
        JsonNode created = post(TOKENS, a, Map.of("name", "my laptop"), 201);
        String token = created.path("token").asText();
        assertThat(token).matches("ff_pat_[A-Za-z0-9]{32}");
        assertThat(created.path("prefix").asText()).isEqualTo(token.substring(0, 11));

        JsonNode listed = get(TOKENS, a, 200);
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).path("token").isNull()).isTrue();             // never again
        assertThat(jdbc.queryForObject("SELECT count(*) FROM api_tokens WHERE token_hash = ? OR prefix = ?",
                Integer.class, token, token)).isZero();                       // not stored in the clear

        Account viaToken = withToken(a, token);
        createProject(viaToken, "made-by-a-script");
        assertThat(get("/api/v1/projects", a, 200).toString()).contains("made-by-a-script");
        assertThat(get(TOKENS, a, 200).get(0).path("lastUsedAt").isNull()).isFalse();

        // A token can't mint more tokens or revoke itself - that needs a real sign-in.
        post(TOKENS, viaToken, Map.of("name", "sneaky"), 403);
        call(HttpMethod.DELETE, TOKENS + "/" + created.path("id").asLong(), viaToken, null, 403);

        call(HttpMethod.DELETE, TOKENS + "/" + created.path("id").asLong(), a, null, 204);
        get("/api/v1/projects", viaToken, 401);
        get("/api/v1/projects", withToken(a, "ff_pat_" + "x".repeat(32)), 401);
    }

    @Test
    void mcpActsAsTheTokensOwnerNotTheSharedServiceAccount() {
        Account a = signup("agent-owner");
        Account other = signup("someone-else");
        long theirs = createProject(other, "not yours");
        String token = post(TOKENS, a, Map.of("name", "Claude Desktop"), 201).path("token").asText();

        JsonNode created = mcp(token, "create_project", Map.of("name", "via-mcp"));
        assertThat(created.path("isError").asBoolean()).isFalse();
        JsonNode mine = get("/api/v1/projects", a, 200);
        assertThat(mine.toString()).contains("via-mcp");                       // it's in MY project list
        long id = 0;
        for (JsonNode p : mine) {
            if (p.path("name").asText().equals("via-mcp")) {
                id = p.path("id").asLong();
            }
        }

        llm.then(calls(write("index.html", INDEX.replace("<link rel=\"stylesheet\" href=\"styles.css\">", "")
                .replace("<script src=\"app.js\"></script>", "")))).then(calls(finish("Built.")));
        assertThat(mcp(token, "generate_app", Map.of("project_id", id, "prompt", "hello")).path("isError").asBoolean())
                .isFalse();
        assertThat(mcp(token, "list_project_files", Map.of("project_id", theirs)).path("isError").asBoolean())
                .isTrue();                                                    // someone else's project: refused
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_logs WHERE user_id = ? AND kind = 'AI_TOKENS'",
                Integer.class, a.id())).isPositive();                         // and it's on MY quota
    }
}
