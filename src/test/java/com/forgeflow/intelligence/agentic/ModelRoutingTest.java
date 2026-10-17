package com.forgeflow.intelligence.agentic;

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
import static com.forgeflow.support.ScriptedLlm.write;
import static org.assertj.core.api.Assertions.assertThat;

/** New builds go to the strong model, edits to the fast one; a failing model hands over to the fallback once. */
@TestPropertySource(properties = {
        "forgeflow.llm.model=test-fast",
        "forgeflow.llm.models.strong=test-strong",
        "forgeflow.llm.models.fallback=test-backup"})
class ModelRoutingTest extends ApiTestSupport {

    @Autowired
    ProjectFileService files;

    private long existingSite(Account a) {
        long id = createProject(a, "routing");
        files.write(id, "index.html", INDEX, a.id());
        files.write(id, "styles.css", CSS, a.id());
        files.write(id, "app.js", JS, a.id());
        return id;
    }

    @Test
    void aNewBuildGoesToTheStrongModel() {
        Account a = signup("route-new");
        long id = createProject(a, "new");
        llm.then(calls(write("index.html", INDEX), write("styles.css", CSS), write("app.js", JS)))
           .then(calls(finish("Built.")));

        JsonNode r = post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "a tip calculator"), 200);

        assertThat(llm.models()).containsExactly("test-strong", "test-strong");
        assertThat(r.path("agent").path("tier").asText()).isEqualTo("STRONG");
        assertThat(r.path("agent").path("model").asText()).isEqualTo("test-strong");
    }

    @Test
    void aShortEditGoesToTheFastModelAndALongBriefDoesNot() {
        Account a = signup("route-edit");
        long id = existingSite(a);
        llm.then(calls(finish("Done.")));
        JsonNode edit = post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "make the heading green"), 200);
        assertThat(llm.models()).containsExactly("test-fast");
        assertThat(edit.path("agent").path("tier").asText()).isEqualTo("FAST");

        llm.reset();
        llm.then(calls(finish("Done.")));
        post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "Rework the page: " + "detail ".repeat(100)), 200);
        assertThat(llm.models()).containsExactly("test-strong");
    }

    @Test
    void aFailingModelHandsOverToTheFallbackForTheRestOfTheRun() {
        Account a = signup("route-fallback");
        long id = existingSite(a);
        llm.thenFail("test-fast returned 503: overloaded")
           .then(calls(write("app.js", JS + "\n// tweaked")))
           .then(calls(finish("Done on the backup.")));

        JsonNode r = post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "tweak the script"), 200);

        assertThat(r.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(llm.models()).containsExactly("test-fast", "test-backup", "test-backup");
        assertThat(r.path("agent").path("fellBack").asBoolean()).isTrue();
        assertThat(r.path("agent").path("model").asText()).isEqualTo("test-backup");
        assertThat(r.path("agent").path("guards").toString()).contains("Model fallback").contains("503");
    }

    @Test
    void whenTheFallbackFailsTooTheRunFailsCleanly() {
        Account a = signup("route-both");
        long id = existingSite(a);
        llm.thenFail("test-fast returned 503").thenFail("test-backup returned 503");

        JsonNode r = post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "tweak"), 200);

        assertThat(r.path("status").asText()).isEqualTo("FAILED");
        assertThat(r.path("stopReason").asText()).isEqualTo("ERROR");
        assertThat(llm.models()).containsExactly("test-fast", "test-backup");
    }
}
