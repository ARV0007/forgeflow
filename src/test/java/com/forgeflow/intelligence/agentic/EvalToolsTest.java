package com.forgeflow.intelligence.agentic;

import com.forgeflow.support.ApiTestSupport;
import com.forgeflow.workspace.ProjectFileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static com.forgeflow.support.ScriptedLlm.text;
import static org.assertj.core.api.Assertions.assertThat;

/** What the evals measure with: the rubric judge, and Ask's faithfulness check. */
class EvalToolsTest extends ApiTestSupport {

    @Autowired
    ProjectFileService files;

    private long shop(Account a) {
        long id = createProject(a, "evals");
        files.write(id, "index.html", "<!doctype html><html><body><button id=\"buy\">Buy</button><script src=\"app.js\"></script></body></html>", a.id());
        files.write(id, "app.js", "const VAT = 0.2;\nfunction total(p) { return p * (1 + VAT); }\n", a.id());
        files.write(id, "styles.css", "button { background: green; }", a.id());
        return id;
    }

    @Test
    void theJudgeScoresTheAppOnAFixedRubric() {
        Account a = signup("judge");
        long id = shop(a);
        llm.then(text("Here you go: {\"fulfils_request\": 4, \"works\": 5, \"accessibility\": 2, \"design\": 3, "
                + "\"code_quality\": 9, \"notes\": \"No labels on anything.\"}"));

        JsonNode v = post("/api/v1/projects/" + id + "/judge", a, Map.of("request", "a shop page with a buy button"), 200);

        assertThat(v.path("scores").path("accessibility").asInt()).isEqualTo(2);
        assertThat(v.path("scores").path("code_quality").asInt()).isEqualTo(5);           // clamped to the scale
        assertThat(v.path("overall").asDouble()).isEqualTo(3.8);
        assertThat(v.path("notes").asText()).isEqualTo("No labels on anything.");
        String asked = llm.seen().get(0).get(0).text();
        assertThat(llm.systemPrompts().get(0)).startsWith("You grade a small web app");
        assertThat(asked).startsWith("USER REQUEST\na shop page with a buy button").contains("--- app.js ---").contains("VAT = 0.2");

        llm.then(text("I'd give it a solid B."));
        post("/api/v1/projects/" + id + "/judge", a, Map.of("request", "a shop page"), 400);
        post("/api/v1/projects/" + id + "/judge", a, Map.of("request", ""), 400);
        get("/api/v1/projects/" + id + "/judge", signup("judge-stranger"), 405);
    }

    @Test
    void askCanCheckThatEachCitedClaimIsBackedByItsSources() {
        Account a = signup("faithful");
        long id = shop(a);
        llm.then(text("Prices include VAT at 20% [1]. The buy button is green [3]. Checkout uses Stripe [1]."))
           .then(text("{\"supported\": [1, 2], \"unsupported\": [3]}"));

        JsonNode r = post("/api/v1/projects/" + id + "/ask", a, Map.of("question", "how are prices worked out?", "verify", true), 200);

        JsonNode check = r.path("check");
        assertThat(check.path("claims").asInt()).isEqualTo(3);
        assertThat(check.path("supported").asInt()).isEqualTo(2);
        assertThat(check.path("unsupported").get(0).asText()).isEqualTo("Checkout uses Stripe [1].");
        // The checker saw each claim with only the sources it cites (sources are numbered by path: app.js is [1]).
        String asked = llm.seen().get(1).get(0).text();
        assertThat(llm.systemPrompts().get(1)).startsWith("You check whether claims are supported");
        assertThat(asked).contains("CLAIM 1: Prices include VAT at 20% [1].").contains("[1] app.js")
                .contains("CLAIM 2: The buy button is green [3].").contains("[3] styles.css");
        assertThat(asked.substring(asked.indexOf("CLAIM 1"), asked.indexOf("CLAIM 2"))).doesNotContain("styles.css");
        assertThat(r.path("tokens").asInt()).isEqualTo(240);

        // Without verify: one call, no check.
        llm.reset();
        llm.then(text("Prices include VAT [1]."));
        JsonNode plain = post("/api/v1/projects/" + id + "/ask", a, Map.of("question", "how are prices worked out?"), 200);
        assertThat(plain.path("check").isNull()).isTrue();
        assertThat(llm.seen()).hasSize(1);
    }
}
