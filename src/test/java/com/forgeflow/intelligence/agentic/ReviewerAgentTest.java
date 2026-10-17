package com.forgeflow.intelligence.agentic;

import com.forgeflow.shared.llm.ToolCall;
import com.forgeflow.support.ApiTestSupport;
import com.forgeflow.workspace.ProjectFileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static com.forgeflow.support.ScriptedLlm.CSS;
import static com.forgeflow.support.ScriptedLlm.INDEX;
import static com.forgeflow.support.ScriptedLlm.JS;
import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static com.forgeflow.support.ScriptedLlm.text;
import static com.forgeflow.support.ScriptedLlm.write;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reviewer agent: a second model reads the request and the diff before a
 * run may finish. In these scripts the reviewer's reply is the call right
 * after a passing finish.
 */
@TestPropertySource(properties = "forgeflow.agent.review.enabled=true")
class ReviewerAgentTest extends ApiTestSupport {

    @Autowired
    ProjectFileService files;

    static final String APPROVE = "{\"verdict\": \"approve\", \"summary\": \"Does what was asked.\", \"issues\": []}";

    private long site(Account a) {
        long id = createProject(a, "review");
        files.write(id, "index.html", INDEX, a.id());
        files.write(id, "styles.css", CSS, a.id());
        files.write(id, "app.js", JS, a.id());
        return id;
    }

    private String generate(Account a, long id, Map<String, Object> body) {
        return post("/api/v1/projects/" + id + "/generate", a, body, 200).toString();
    }

    @Test
    void anApprovedChangeFinishesAndTheReviewerSawOnlyTheRequestAndTheDiff() {
        Account a = signup("review-ok");
        long id = site(a);
        llm.then(calls(write("styles.css", "h1 { color: green; }")))
           .then(calls(finish("Made the heading green.")))
           .then(text(APPROVE));

        JsonNode r = post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "make the heading green"), 200);

        assertThat(r.path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode review = r.path("agent").path("review");
        assertThat(review.path("verdict").asText()).isEqualTo("approve");
        assertThat(review.path("rounds").asInt()).isEqualTo(1);
        // A separate agent: its own instructions, one message, the request and a real diff - not the builder's history.
        assertThat(llm.systemPrompts().get(2)).startsWith("You review code changes");
        assertThat(llm.seen().get(2)).hasSize(1);
        assertThat(llm.seen().get(2).get(0).text()).contains("USER REQUEST\nmake the heading green")
                .contains("--- styles.css ---").contains("-h1 { color: navy; }").contains("+h1 { color: green; }")
                .doesNotContain("--- app.js ---");
    }

    @Test
    void majorProblemsGoBackToTheBuilderOnceAndTheSecondLookIsFinal() {
        Account a = signup("review-fix");
        long id = site(a);
        llm.then(calls(write("app.js", "document.querySelector('#missing').addEventListener('click', () => {});")))
           .then(calls(finish("Wired the button.")))
           .then(text("{\"verdict\": \"changes\", \"summary\": \"The handler targets an element that doesn't exist.\", "
                   + "\"issues\": [{\"severity\": \"major\", \"file\": \"app.js\", \"problem\": \"#missing is not in index.html; select h1 instead\"}, "
                   + "{\"severity\": \"minor\", \"file\": \"app.js\", \"problem\": \"name the handler\"}]}"))
           .then(calls(write("app.js", JS)))
           .then(calls(finish("Wired the heading.")))
           .then(text(APPROVE));

        JsonNode r = post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "make the heading clickable"), 200);

        assertThat(r.path("status").asText()).isEqualTo("SUCCEEDED");
        // The builder was told exactly what the reviewer found - major issues only.
        var afterReview = llm.seen().get(3);
        String feedback = afterReview.get(afterReview.size() - 1).toolResults().get(0).output();
        assertThat(feedback).contains("A reviewer checked your changes").contains("app.js: #missing is not in index.html")
                .doesNotContain("name the handler");
        // The second review saw the corrected file against the ORIGINAL - the whole run's change.
        assertThat(llm.seen().get(5).get(0).text()).doesNotContain("#missing");
        JsonNode review = r.path("agent").path("review");
        assertThat(review.path("verdict").asText()).isEqualTo("approve");
        assertThat(review.path("rounds").asInt()).isEqualTo(2);
        assertThat(r.path("summary").asText()).isEqualTo("Wired the heading.");
    }

    @Test
    void aReviewerThatStillObjectsAfterTheFixRoundDoesNotBlockTheRun() {
        Account a = signup("review-stubborn");
        long id = site(a);
        String objection = "{\"verdict\": \"changes\", \"summary\": \"Still not right.\", "
                + "\"issues\": [{\"severity\": \"major\", \"file\": \"styles.css\", \"problem\": \"use a darker green\"}]}";
        llm.then(calls(write("styles.css", "h1 { color: lime; }"))).then(calls(finish("Green.")))
           .then(text(objection))
           .then(calls(write("styles.css", "h1 { color: limegreen; }"))).then(calls(finish("Greener.")))
           .then(text(objection));

        JsonNode r = post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "make the heading green"), 200);

        assertThat(r.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(r.path("agent").path("review").path("verdict").asText()).isEqualTo("changes");
        assertThat(r.path("agent").path("review").path("issues").get(0).path("problem").asText()).isEqualTo("use a darker green");
        assertThat(llm.seen()).hasSize(6);
    }

    @Test
    void anUnreadableReviewIsSkippedNotABlocker() {
        Account a = signup("review-garbled");
        long id = site(a);
        llm.then(calls(write("styles.css", "h1 { color: green; }"))).then(calls(finish("Green.")))
           .then(text("Looks fine to me!"));

        JsonNode r = post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "make the heading green"), 200);

        assertThat(r.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(r.path("agent").path("review").path("verdict").asText()).isEqualTo("skipped");
        assertThat(r.path("agent").path("guards").toString()).contains("Review skipped").contains("no JSON");
    }

    @Test
    void aRequestCanSwitchTheReviewerOffAndARunWithNoChangesIsNotReviewed() {
        Account a = signup("review-off");
        long id = site(a);
        llm.then(calls(write("styles.css", "h1 { color: green; }"))).then(calls(finish("Green.")));
        JsonNode off = post("/api/v1/projects/" + id + "/generate", a,
                Map.of("prompt", "make the heading green", "review", false), 200);
        assertThat(off.path("agent").path("review").isNull()).isTrue();
        assertThat(llm.seen()).hasSize(2);

        llm.reset();
        llm.then(calls(new ToolCall("read_file", Map.of("path", "index.html")))).then(calls(finish("Nothing to change.")));
        JsonNode readOnly = post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "is the title right?"), 200);
        assertThat(readOnly.path("agent").path("review").isNull()).isTrue();
        assertThat(llm.seen()).hasSize(2);
    }
}
