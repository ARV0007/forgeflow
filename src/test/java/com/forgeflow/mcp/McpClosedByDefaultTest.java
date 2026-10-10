package com.forgeflow.mcp;

import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The production default: /mcp answers only to a personal access token (or the shared key, if set). */
@TestPropertySource(properties = "forgeflow.mcp.allow-anonymous=false")
class McpClosedByDefaultTest extends ApiTestSupport {

    static final Map<String, Object> LIST = Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list");

    @Test
    void noTokenNoToolsAndAPersonalTokenOpensIt() {
        post("/mcp", null, LIST, 401);
        post("/mcp", new Account(null, null, "ff_pat_" + "z".repeat(32)), LIST, 401);

        Account a = signup("closed-mcp");
        String token = post("/api/v1/me/tokens", a, Map.of("name", "agent"), 201).path("token").asText();
        assertThat(post("/mcp", new Account(null, null, token), LIST, 200).path("result").path("tools")).isNotEmpty();
    }
}
