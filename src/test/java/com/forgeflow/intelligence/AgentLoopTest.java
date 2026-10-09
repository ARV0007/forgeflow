package com.forgeflow.intelligence;

import com.forgeflow.shared.llm.LlmMessage;
import com.forgeflow.shared.llm.ToolResult;
import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static com.forgeflow.support.ScriptedLlm.BROKEN_JS;
import static com.forgeflow.support.ScriptedLlm.CSS;
import static com.forgeflow.support.ScriptedLlm.INDEX;
import static com.forgeflow.support.ScriptedLlm.JS;
import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static com.forgeflow.support.ScriptedLlm.text;
import static com.forgeflow.support.ScriptedLlm.write;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agent loop and its build gate, run for real against a scripted model.
 *
 * The self-healing loop is ForgeFlow's defining feature, and until this test
 * it had only ever been demonstrated by hand. These cases pin down every way
 * a run can end.
 */
class AgentLoopTest extends ApiTestSupport {

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    private JsonNode generate(Account a, long projectId) {
        return post("/api/v1/projects/" + projectId + "/generate", a, Map.of("prompt", "build a page"), 200);
    }

    @Test
    void aCleanRunFinishesThroughTheGate() {
        Account a = signup("agent");
        long id = createProject(a, "clean");
        llm.then(calls(write("index.html", INDEX), write("styles.css", CSS), write("app.js", JS)))
           .then(calls(finish("Built a page.")));

        JsonNode run = generate(a, id);

        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(run.path("stopReason").asText()).isEqualTo("FINISH_TOOL");
        assertThat(run.path("buildPassed").asBoolean()).isTrue();
        assertThat(run.path("repairRounds").asInt()).isZero();
        assertThat(run.path("summary").asText()).isEqualTo("Built a page.");
        assertThat(get("/api/v1/projects/" + id + "/files", a, 200)).hasSize(3);
    }

    /**
     * THE feature. finish is refused while the build fails; the refusal reaches
     * the model as a tool result naming the broken file; the model fixes it and
     * finish then succeeds.
     */
    @Test
    void aFailingBuildIsFedBackAndRepaired() {
        Account a = signup("agent");
        long id = createProject(a, "healing");
        llm.then(calls(write("index.html", INDEX), write("styles.css", CSS), write("app.js", BROKEN_JS)))
           .then(calls(finish("Built it.")))                       // refused: app.js does not parse
           .then(calls(write("app.js", JS)))                      // the repair
           .then(calls(finish("Built it, and fixed app.js.")));

        JsonNode run = generate(a, id);

        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(run.path("repairRounds").asInt()).isEqualTo(1);
        assertThat(run.path("buildPassed").asBoolean()).isTrue();

        // What the model was shown after its first finish: a FAILED finish
        // result that names the broken file. That is the feedback loop.
        List<LlmMessage> afterRefusal = llm.seen().get(2);
        ToolResult refusal = afterRefusal.get(afterRefusal.size() - 1).toolResults().get(0);
        assertThat(refusal.toolName()).isEqualTo("finish");
        assertThat(refusal.ok()).isFalse();
        assertThat(refusal.output()).contains("You cannot finish yet").contains("app.js");
    }

    @Test
    void repairsAreCappedAndTheRunFailsHonestly() {
        Account a = signup("agent");
        long id = createProject(a, "hopeless");
        llm.then(calls(write("index.html", INDEX), write("styles.css", CSS), write("app.js", BROKEN_JS)));
        for (int i = 0; i < 4; i++) {
            llm.then(calls(finish("Surely done now.")));   // never actually fixes anything
        }

        JsonNode run = generate(a, id);

        assertThat(run.path("status").asText()).isEqualTo("FAILED");
        assertThat(run.path("stopReason").asText()).isEqualTo("MAX_REPAIRS");
        assertThat(run.path("repairRounds").asInt()).isEqualTo(3);
        assertThat(run.path("buildPassed").asBoolean()).isFalse();
    }

    /** A model that just stops talking does not get to skip the inspection. */
    @Test
    void stoppingWithoutFinishStillRunsTheBuild() {
        Account a = signup("agent");
        long id = createProject(a, "quiet");
        llm.then(calls(write("index.html", INDEX), write("styles.css", CSS), write("app.js", JS)))
           .then(text("All done!"));

        JsonNode run = generate(a, id);

        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(run.path("stopReason").asText()).isEqualTo("NO_TOOL_CALL");
        assertThat(run.path("buildPassed").asBoolean()).isTrue();
    }

    @Test
    void thePathGuardTurnsEscapeAttemptsIntoObservations() {
        Account a = signup("agent");
        long id = createProject(a, "guarded");
        llm.then(calls(write("../../etc/passwd", "owned")))
           .then(calls(write("index.html", INDEX), write("styles.css", CSS), write("app.js", JS)))
           .then(calls(finish("Built it.")));

        JsonNode run = generate(a, id);

        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
        ToolResult rejected = llm.seen().get(1).get(llm.seen().get(1).size() - 1).toolResults().get(0);
        assertThat(rejected.ok()).isFalse();
        assertThat(rejected.output()).contains("..");
        for (JsonNode f : get("/api/v1/projects/" + id + "/files", a, 200)) {
            assertThat(f.path("path").asText()).doesNotContain("..");
        }
    }

    @Test
    void cachedPromptTokensAreSummedOntoTheRun() {
        Account a = signup("cache");
        long id = createProject(a, "cache");
        llm.then(new com.forgeflow.shared.llm.LlmResponse(null,
                        java.util.List.of(write("index.html", INDEX), write("styles.css", CSS), write("app.js", JS)),
                        "[]", 1000, 50, 1050, 0))
           .then(new com.forgeflow.shared.llm.LlmResponse(null, java.util.List.of(finish("Built.")),
                        "[]", 1200, 10, 1210, 900));     // round two re-sends round one's prefix

        long runId = generate(a, id).path("runId").asLong();

        assertThat(jdbc.queryForObject("SELECT cached_tokens FROM generation_runs WHERE id = ?", Integer.class, runId))
                .isEqualTo(900);
    }

    @Test
    void aProviderFailureEndsTheRunAsAnErrorWithItsReason() {
        Account a = signup("agent");
        long id = createProject(a, "dead-provider");
        // nothing scripted: the model call throws, like an unreachable provider

        JsonNode run = generate(a, id);

        assertThat(run.path("status").asText()).isEqualTo("FAILED");
        assertThat(run.path("stopReason").asText()).isEqualTo("ERROR");
        assertThat(run.path("errorMessage").asText()).contains("nothing left in the script");
    }
}
