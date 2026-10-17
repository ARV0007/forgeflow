package com.forgeflow.intelligence.agentic;

import com.forgeflow.shared.llm.ToolCall;
import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.forgeflow.support.ScriptedLlm.CSS;
import static com.forgeflow.support.ScriptedLlm.INDEX;
import static com.forgeflow.support.ScriptedLlm.JS;
import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static com.forgeflow.support.ScriptedLlm.write;
import static org.assertj.core.api.Assertions.assertThat;

/** Plan, then build: the agent writes a plan, ticks it off, and can't finish with steps left open. */
class PlanThenBuildTest extends ApiTestSupport {

    /** update_plan with steps "title=status". */
    static ToolCall plan(String... steps) {
        return new ToolCall("update_plan", Map.of("steps", Arrays.stream(steps).map(s -> {
            String[] p = s.split("=");
            return Map.of("title", p[0], "status", p[1]);
        }).toList()));
    }

    private String lastToolOutput(int call, int index) {
        List<com.forgeflow.shared.llm.LlmMessage> h = llm.seen().get(call);
        return h.get(h.size() - 1).toolResults().get(index).output();
    }

    @Test
    void thePlanIsFollowedAndReturnedWithTheRun() {
        Account a = signup("plan");
        long id = createProject(a, "plan");
        llm.then(calls(plan("Page structure=in_progress", "Styles=pending", "Click counter=pending"),
                        write("index.html", INDEX)))
           .then(calls(write("styles.css", CSS), write("app.js", JS),
                        plan("Page structure=done", "Styles=done", "Click counter=done")))
           .then(calls(finish("Built it in three steps.")));

        JsonNode r = post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "a click counter page"), 200);

        assertThat(r.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(lastToolOutput(1, 0)).isEqualTo("Plan saved: 0 of 3 steps done. Next: Page structure");
        assertThat(lastToolOutput(2, 2)).contains("3 of 3 steps done").contains("call finish");
        JsonNode plan = r.path("agent").path("plan");
        assertThat(plan).hasSize(3);
        assertThat(plan.get(2).path("title").asText()).isEqualTo("Click counter");
        assertThat(plan.get(2).path("status").asText()).isEqualTo("done");
        assertThat(llm.systemPrompts().get(0)).contains("PLANNING").contains("update_plan");
    }

    @Test
    void finishIsTurnedBackWhileStepsAreOpenThenTheAgentSkipsOne() {
        Account a = signup("plan-gate");
        long id = createProject(a, "gate");
        llm.then(calls(plan("Build the page=done", "Add a dark mode=pending"),
                        write("index.html", INDEX), write("styles.css", CSS), write("app.js", JS)))
           .then(calls(finish("Done.")))                                     // too early
           .then(calls(plan("Build the page=done", "Add a dark mode (not asked for after all)=skipped")))
           .then(calls(finish("Built the page.")));

        JsonNode r = post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "a page"), 200);

        assertThat(r.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(lastToolOutput(2, 0)).startsWith("ERROR: Your plan still has unfinished steps: 'Add a dark mode'");
        assertThat(r.path("repairRounds").asInt()).isZero();                  // a plan check isn't a build repair
        assertThat(r.path("agent").path("plan").get(1).path("status").asText()).isEqualTo("skipped");
    }

    @Test
    void thePlanCheckIsBoundedSoARunStillEnds() {
        Account a = signup("plan-stuck");
        long id = createProject(a, "stuck");
        llm.then(calls(plan("Everything=pending"), write("index.html", INDEX), write("styles.css", CSS), write("app.js", JS)))
           .then(calls(finish("Done.")))
           .then(calls(finish("Done.")))
           .then(calls(finish("Really done.")));                             // third time: the build gate decides

        JsonNode r = post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "a page"), 200);

        assertThat(r.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(r.path("summary").asText()).isEqualTo("Really done.");
    }

    @Test
    void aMalformedPlanIsRefusedWithAReason() {
        Account a = signup("plan-bad");
        long id = createProject(a, "bad");
        llm.then(calls(new ToolCall("update_plan", Map.of("steps", List.of(Map.of("title", "x", "status", "maybe"))))))
           .then(calls(write("index.html", INDEX), write("styles.css", CSS), write("app.js", JS)))
           .then(calls(finish("Built.")));

        JsonNode r = post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "a page"), 200);

        assertThat(lastToolOutput(1, 0)).contains("status must be one of pending, in_progress, done, skipped");
        assertThat(r.path("agent").path("plan")).isEmpty();
    }

    @Test
    void aChatReplyKeepsItsPlanAfterReload() {
        Account a = signup("plan-chat");
        long id = createProject(a, "chat");
        long session = post("/api/v1/projects/" + id + "/chat/sessions", a, Map.of(), 201).path("id").asLong();
        llm.then(calls(plan("Write the page=done"), write("index.html", INDEX), write("styles.css", CSS), write("app.js", JS)))
           .then(calls(finish("Built.")));
        post("/api/v1/projects/" + id + "/chat/sessions/" + session + "/messages", a, Map.of("content", "a page"), 200);

        JsonNode history = get("/api/v1/projects/" + id + "/chat/sessions/" + session + "/messages", a, 200);
        JsonNode reply = history.get(history.size() - 1);
        assertThat(reply.path("agent").path("plan").get(0).path("title").asText()).isEqualTo("Write the page");
        assertThat(reply.path("agent").path("trajectory").toString()).contains("update_plan").contains("finish");
    }
}
