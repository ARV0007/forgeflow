package com.forgeflow.shared.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitRulesTest {

    private final RateLimitRules rules = new RateLimitRules(10, 6, 30, 20, 300);

    private String bucket(String method, String path, Long user) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, path);
        r.setRemoteAddr("203.0.113.7");
        RateLimitRules.Match m = rules.match(r, user);
        return m == null ? null : m.limit().name() + " " + m.key();
    }

    @Test
    void eachRequestLandsInExactlyTheRightBucket() {
        assertThat(bucket("POST", "/api/v1/auth/login", null)).isEqualTo("auth ip:203.0.113.7");
        assertThat(bucket("POST", "/api/v1/auth/signup", null)).isEqualTo("auth ip:203.0.113.7");
        assertThat(bucket("POST", "/mcp", null)).isEqualTo("mcp ip:203.0.113.7");

        assertThat(bucket("POST", "/api/v1/projects/4/generate", 9L)).isEqualTo("ai user:9");
        assertThat(bucket("POST", "/api/v1/projects/4/generate/stream", 9L)).isEqualTo("ai user:9");
        assertThat(bucket("POST", "/api/v1/projects/4/chat/sessions/2/messages", 9L)).isEqualTo("ai user:9");
        assertThat(bucket("POST", "/api/v1/projects/4/chat/sessions/2/messages/stream", 9L)).isEqualTo("ai user:9");
        assertThat(bucket("POST", "/api/v1/projects/4/chat/sessions/2/retry/stream", 9L)).isEqualTo("ai user:9");
        assertThat(bucket("POST", "/api/v1/projects/4/members", 9L)).isEqualTo("invites user:9");

        // Reading chat history is not a model call.
        assertThat(bucket("GET", "/api/v1/projects/4/chat/sessions/2/messages", 9L)).isEqualTo("api user:9");
        assertThat(bucket("POST", "/api/v1/projects", 9L)).isEqualTo("api user:9");

        // Not limited: anonymous non-auth calls (they 401 anyway) and preview content.
        assertThat(bucket("GET", "/api/v1/projects", null)).isNull();
        assertThat(bucket("GET", "/p/abc/index.html", null)).isNull();
    }
}
