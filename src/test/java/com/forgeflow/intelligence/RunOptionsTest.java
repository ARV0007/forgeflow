package com.forgeflow.intelligence;

import com.forgeflow.support.ApiTestSupport;
import com.forgeflow.workspace.ProjectFileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static com.forgeflow.support.ScriptedLlm.CSS;
import static com.forgeflow.support.ScriptedLlm.INDEX;
import static com.forgeflow.support.ScriptedLlm.JS;
import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static com.forgeflow.support.ScriptedLlm.write;
import static org.assertj.core.api.Assertions.assertThat;

/** The measuring knobs: retrieval can be forced on, switched off, and is reported per run. */
class RunOptionsTest extends ApiTestSupport {

    @Autowired
    ProjectFileService files;

    private long smallProject(Account a) {
        long id = createProject(a, "rag-knobs");
        files.write(id, "index.html", INDEX, a.id());
        files.write(id, "styles.css", CSS, a.id());
        files.write(id, "app.js", JS, a.id());
        return id;
    }

    private JsonNode generate(Account a, long id, Map<String, Object> body) {
        llm.then(calls(write("app.js", JS))).then(calls(finish("ok")));
        int before = llm.seen().size();
        JsonNode r = post("/api/v1/projects/" + id + "/generate", a, body, 200);
        lastRequest = llm.seen().get(before).get(0).text();
        return r;
    }

    private String lastRequest;

    @Test
    void byDefaultASmallProjectGetsNotesButNoRetrievedCode() {
        Account a = signup("rag-default");
        long id = smallProject(a);
        JsonNode r = generate(a, id, Map.of("prompt", "make the heading respond to clicks"));
        assertThat(r.path("rag").path("codeExcerpts").asInt()).isZero();
        assertThat(r.path("rag").path("notes").asInt()).isPositive();
        assertThat(lastRequest).doesNotContain("RELEVANT CODE").contains("REFERENCE NOTES");
    }

    @Test
    void codeCanBeForcedOnAndNotesSwitchedOff() {
        Account a = signup("rag-forced");
        long id = smallProject(a);
        JsonNode r = generate(a, id, Map.of("prompt", "make the heading respond to clicks",
                "codeContext", "always", "notes", 0));
        assertThat(r.path("rag").path("codeExcerpts").asInt()).isPositive();
        assertThat(r.path("rag").path("notes").asInt()).isZero();
        assertThat(lastRequest).contains("RELEVANT CODE").doesNotContain("REFERENCE NOTES");
    }

    @Test
    void retrievalCanBeSwitchedOffEntirely() {
        Account a = signup("rag-off");
        long id = smallProject(a);
        JsonNode r = generate(a, id, Map.of("prompt", "make the heading respond to clicks", "codeContext", "off", "notes", 0));
        assertThat(r.path("rag").path("codeExcerpts").asInt()).isZero();
        assertThat(lastRequest).isEqualTo("make the heading respond to clicks");
    }

    @Test
    void badKnobsAreRefused() {
        Account a = signup("rag-bad");
        long id = smallProject(a);
        post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "x", "codeContext", "sometimes"), 400);
        post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "x", "notes", 9), 400);
    }
}
