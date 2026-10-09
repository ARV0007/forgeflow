package com.forgeflow.intelligence;

import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Map;

import static com.forgeflow.support.ScriptedLlm.CSS;
import static com.forgeflow.support.ScriptedLlm.INDEX;
import static com.forgeflow.support.ScriptedLlm.JS;
import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static com.forgeflow.support.ScriptedLlm.write;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runtime half of self-healing. The build gate proves the code parses;
 * the preview proves - or disproves - that it runs. Errors the generated app
 * threw in a real browser reach the agent with the next request.
 */
class RuntimeErrorsTest extends ApiTestSupport {

    private String token(Account a, long id) {
        String url = post("/api/v1/projects/" + id + "/preview", a, null, 200).path("url").asText();
        return url.split("/")[2];
    }

    private void browserReports(String token, String level, String message) throws Exception {
        int status = mvc.perform(MockMvcRequestBuilders.post("/p/" + token + "/__log")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(json.writeValueAsString(Map.of("level", level, "message", message))))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(204);
    }

    private long site(Account a) {
        long id = createProject(a, "runtime");
        llm.then(calls(write("index.html", INDEX), write("styles.css", CSS), write("app.js", JS)))
           .then(calls(finish("Built.")));
        post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "build"), 200);
        return id;
    }

    @Test
    void errorsFromThePreviewArriveWithTheNextRequest() throws Exception {
        Account a = signup("runtime");
        long id = site(a);
        String token = token(a, id);

        browserReports(token, "error", "Uncaught TypeError: total is undefined (app.js:3)");
        browserReports(token, "error", "Uncaught TypeError: total is undefined (app.js:3)");   // same one again
        browserReports(token, "warn", "deprecated API");                                      // not an error
        browserReports(token, "error", "Unhandled promise rejection: 404");

        llm.then(calls(finish("Fixed it.")));
        post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "fix the error"), 200);

        String request = llm.seen().get(2).get(0).text();
        assertThat(request).startsWith("fix the error").contains("RUNTIME ERRORS")
                .contains("- Uncaught TypeError: total is undefined (app.js:3)")
                .contains("- Unhandled promise rejection: 404")
                .doesNotContain("deprecated API");
        // Listed once, however many times the browser hit it.
        assertThat(request.split("total is undefined", -1)).hasSize(2);
    }

    @Test
    void errorsFromBeforeTheLastBuildAreOldNews() throws Exception {
        Account a = signup("stale");
        long id = site(a);
        String token = token(a, id);

        browserReports(token, "error", "Uncaught ReferenceError: oldBug is not defined");
        post("/api/v1/projects/" + id + "/build", a, null, 200);   // the code was rebuilt since

        llm.then(calls(finish("Nothing to do.")));
        post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "tweak"), 200);

        assertThat(llm.seen().get(2).get(0).text()).isEqualTo("tweak");
    }

    @Test
    void aCleanPreviewAddsNothing() {
        Account a = signup("clean");
        long id = site(a);
        llm.then(calls(finish("Done.")));
        post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "tweak"), 200);
        assertThat(llm.seen().get(2).get(0).text()).isEqualTo("tweak");
    }
}
