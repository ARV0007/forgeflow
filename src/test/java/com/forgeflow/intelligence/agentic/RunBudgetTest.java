package com.forgeflow.intelligence.agentic;

import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static com.forgeflow.support.ScriptedLlm.CSS;
import static com.forgeflow.support.ScriptedLlm.INDEX;
import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.read;
import static com.forgeflow.support.ScriptedLlm.write;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A cost limit per run, with a warning first. Each scripted call costs about
 * $0.000055 at the fast model's list price, so a $0.0001 limit allows two
 * calls; the warning (at half, here) comes after the first.
 */
@TestPropertySource(properties = {"forgeflow.agent.max-cost-usd=0.0001", "forgeflow.agent.wrap-up-at=0.5"})
class RunBudgetTest extends ApiTestSupport {

    @Test
    void theAgentIsWarnedThenStoppedAtTheCostLimit() {
        Account a = signup("budget");
        long id = createProject(a, "budget");
        llm.then(calls(write("index.html", INDEX)))
           .then(calls(write("styles.css", CSS)))
           .then(calls(read("index.html")));          // never asked for: the limit is reached first

        JsonNode r = post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "a page"), 200);

        assertThat(r.path("status").asText()).isEqualTo("CAPPED");
        assertThat(r.path("stopReason").asText()).isEqualTo("COST_BUDGET");
        assertThat(llm.seen()).hasSize(2);
        // The warning rode on the first round's tool result, which the model reads.
        String afterFirst = llm.seen().get(1).get(llm.seen().get(1).size() - 1).toolResults().get(0).output();
        assertThat(afterFirst).contains("Wrote index.html").contains("NOTE: most of this run's budget");
        assertThat(r.path("agent").path("guards").toString()).contains("asked the agent to wrap up").contains("cost limit");
        assertThat(r.path("costUsd").decimalValue()).isGreaterThanOrEqualTo(new java.math.BigDecimal("0.0001"));
    }
}
