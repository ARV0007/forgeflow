package com.forgeflow.execution;

import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static com.forgeflow.support.ScriptedLlm.CSS;
import static com.forgeflow.support.ScriptedLlm.INDEX;
import static com.forgeflow.support.ScriptedLlm.JS;
import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static com.forgeflow.support.ScriptedLlm.write;
import static org.assertj.core.api.Assertions.assertThat;

/** code.generated's second consumer: a running preview's logs hear about new code. */
class CodeChangeNotifierTest extends ApiTestSupport {

    private void build(Account a, long id, String prompt) {
        llm.then(calls(write("index.html", INDEX), write("styles.css", CSS), write("app.js", JS)))
           .then(calls(finish("Built.")));
        post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", prompt), 200);
    }

    private boolean logsMention(Account a, long id, String text) {
        for (JsonNode line : get("/api/v1/projects/" + id + "/preview/logs", a, 200)) {
            if (line.path("message").asText().contains(text)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aRunWhileThePreviewIsOpenIsAnnouncedInItsLogs() {
        Account a = signup("watcher");
        long id = createProject(a, "watched");
        build(a, id, "first");
        assertThat(logsMention(a, id, "reload the preview")).isFalse();     // no preview running: nothing to tell

        post("/api/v1/projects/" + id + "/preview", a, null, 200);
        build(a, id, "second");
        assertThat(logsMention(a, id, "changed index.html, styles.css, app.js - reload the preview")).isTrue();
    }
}
