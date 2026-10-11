package com.forgeflow.intelligence;

import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static com.forgeflow.support.ScriptedLlm.CSS;
import static com.forgeflow.support.ScriptedLlm.INDEX;
import static com.forgeflow.support.ScriptedLlm.JS;
import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static com.forgeflow.support.ScriptedLlm.write;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Waiting out the provider's rate limit is not the run's fault, so it doesn't
 * spend the run's time budget. A 1-second budget, two model calls that each
 * wait 1.2 s behind a 429 first: the run still finishes. Without the deadline
 * extension it stops with TIMEOUT after the first call.
 */
class RateLimitWaitTest extends ApiTestSupport {

    @DynamicPropertySource
    static void shortBudget(DynamicPropertyRegistry r) {
        r.add("forgeflow.agent.timeout-seconds", () -> "1");
    }

    @Test
    void rateLimitWaitsDoNotCountAgainstTheRun() {
        Account a = signup("backoff");
        long id = createProject(a, "patient");
        llm.thenAfterRateLimit(1200, calls(write("index.html", INDEX), write("styles.css", CSS), write("app.js", JS)))
           .thenAfterRateLimit(1200, calls(finish("Built it, slowly.")));

        JsonNode run = post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "build a page"), 200);

        assertThat(run.path("stopReason").asText()).isEqualTo("FINISH_TOOL");
        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
    }
}
