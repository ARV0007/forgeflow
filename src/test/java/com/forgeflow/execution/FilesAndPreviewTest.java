package com.forgeflow.execution;

import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.forgeflow.support.ScriptedLlm.CSS;
import static com.forgeflow.support.ScriptedLlm.INDEX;
import static com.forgeflow.support.ScriptedLlm.JS;
import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static com.forgeflow.support.ScriptedLlm.write;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec: Files (tree, content, zip download) and Preview (get preview, logs
 * stream), end to end.
 */
class FilesAndPreviewTest extends ApiTestSupport {

    private static String project(long id) {
        return "/api/v1/projects/" + id;
    }

    /** A clean three-file site, written by whoever runs it. */
    private void buildSite(Account as, long projectId) {
        llm.then(calls(write("index.html", INDEX), write("styles.css", CSS), write("app.js", JS)))
           .then(calls(finish("Built a page.")));
        post(project(projectId) + "/generate", as, Map.of("prompt", "build a page"), 200);
    }

    private MvcResult raw(String path, Account as) throws Exception {
        var req = MockMvcRequestBuilders.get(path);
        if (as != null) {
            req.header("Authorization", "Bearer " + as.token());
        }
        return mvc.perform(req).andReturn();
    }

    // ---------------------------------------------------------------- files

    @Test
    void theZipHoldsEveryFileUnderOneProjectFolder() throws Exception {
        Account owner = signup("owner");
        long id = call(HttpMethod.POST, "/api/v1/projects", owner,
                Map.of("name", "My Todo App!", "description", "x"), 201).path("id").asLong();
        buildSite(owner, id);

        MvcResult res = raw(project(id) + "/files/download", owner);

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(res.getResponse().getContentType()).isEqualTo("application/zip");
        assertThat(res.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("attachment").contains("my-todo-app.zip");

        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(res.getResponse().getContentAsByteArray()))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                entries.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(entries).containsOnlyKeys("my-todo-app/app.js", "my-todo-app/index.html", "my-todo-app/styles.css");
        assertThat(entries.get("my-todo-app/index.html")).isEqualTo(INDEX);
        assertThat(entries.get("my-todo-app/styles.css")).isEqualTo(CSS);
    }

    @Test
    void downloadingIsARead() throws Exception {
        Account owner = signup("owner");
        Account viewer = signup("viewer");
        Account stranger = signup("stranger");
        long id = createProject(owner, "shared");
        buildSite(owner, id);
        post(project(id) + "/members", owner, Map.of("email", viewer.email(), "role", "VIEWER"), 201);

        assertThat(raw(project(id) + "/files/download", viewer).getResponse().getStatus()).isEqualTo(200);
        assertThat(raw(project(id) + "/files/download", stranger).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void anEmptyProjectStillDownloadsAsAValidZip() throws Exception {
        Account owner = signup("owner");
        long id = createProject(owner, "!!!");

        MvcResult res = raw(project(id) + "/files/download", owner);

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        // A name with nothing usable in it falls back to the id.
        assertThat(res.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("project-" + id + ".zip");
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(res.getResponse().getContentAsByteArray()))) {
            assertThat(zip.getNextEntry()).isNull();
        }
    }

    @Test
    void filesRecordWhoCreatedAndWhoLastChangedThem() {
        Account owner = signup("owner");
        Account editor = signup("editor");
        long id = createProject(owner, "authorship");
        buildSite(owner, id);
        post(project(id) + "/members", owner, Map.of("email", editor.email(), "role", "EDITOR"), 201);

        llm.then(calls(write("index.html", INDEX.replace("Hello", "Hello again"))))
           .then(calls(finish("Changed the heading.")));
        post(project(id) + "/generate", editor, Map.of("prompt", "change the heading"), 200);

        Map<String, JsonNode> tree = new LinkedHashMap<>();
        get(project(id) + "/files", owner, 200).forEach(f -> tree.put(f.path("path").asText(), f));

        JsonNode index = tree.get("index.html");
        assertThat(index.path("createdBy").asLong()).isEqualTo(owner.id());
        assertThat(index.path("updatedBy").asLong()).isEqualTo(editor.id());
        assertThat(index.path("version").asInt()).isEqualTo(2);

        JsonNode css = tree.get("styles.css");
        assertThat(css.path("createdBy").asLong()).isEqualTo(owner.id());
        assertThat(css.path("updatedBy").asLong()).isEqualTo(owner.id());
        assertThat(css.path("version").asInt()).isEqualTo(1);
    }

    // -------------------------------------------------------------- preview

    @Test
    void getPreviewFollowsItsLifecycle() {
        Account owner = signup("owner");
        Account viewer = signup("viewer");
        long id = createProject(owner, "preview");
        post(project(id) + "/members", owner, Map.of("email", viewer.email(), "role", "VIEWER"), 201);

        // Nothing to preview yet: a 409 that says why, not a 500.
        assertThat(post(project(id) + "/preview", owner, null, 409).path("detail").asText()).contains("no files");
        get(project(id) + "/preview", owner, 404);

        buildSite(owner, id);
        JsonNode started = post(project(id) + "/preview", owner, null, 200);
        assertThat(started.path("status").asText()).isEqualTo("RUNNING");
        assertThat(started.path("url").asText()).startsWith("/p/");

        // A viewer can look at it but not start or stop one.
        assertThat(get(project(id) + "/preview", viewer, 200).path("url").asText())
                .isEqualTo(started.path("url").asText());
        call(HttpMethod.DELETE, project(id) + "/preview", viewer, null, 403);

        call(HttpMethod.DELETE, project(id) + "/preview", owner, null, 204);
        get(project(id) + "/preview", owner, 404);
    }

    @Test
    void servedHtmlCarriesTheConsoleBridgeAndStaysSandboxed() throws Exception {
        Account owner = signup("owner");
        long id = createProject(owner, "bridge");
        buildSite(owner, id);
        String url = post(project(id) + "/preview", owner, null, 200).path("url").asText();
        String token = url.split("/")[2];

        MvcResult page = raw(url, null);
        String html = page.getResponse().getContentAsString();

        assertThat(page.getResponse().getHeader("Content-Security-Policy")).contains("sandbox").doesNotContain("allow-same-origin");
        assertThat(html).contains("/p/" + token + "/__log");
        assertThat(html.indexOf("__log")).isLessThan(html.indexOf("styles.css"));   // before the page's own content
        assertThat(html).contains("<h1>Hello</h1>");

        // Non-HTML files are served untouched.
        assertThat(raw("/p/" + token + "/app.js", null).getResponse().getContentAsString()).isEqualTo(JS);

        // The snapshot half of the bridge: it answers only its parent window, and
        // loads the vendored html-to-image from under the same token, on demand.
        assertThat(html).contains("ff:snapshot").contains("e.source!==parent").contains("/p/" + token + "/__snapshot.js");
        MvcResult lib = raw("/p/" + token + "/__snapshot.js", null);
        assertThat(lib.getResponse().getStatus()).isEqualTo(200);
        assertThat(lib.getResponse().getContentType()).startsWith("text/javascript");
        assertThat(lib.getResponse().getContentAsString()).startsWith("/*! html-to-image 1.11.13");
        assertThat(raw("/p/not-a-token/__snapshot.js", null).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void theLogCollectsBuildsPreviewTrafficAndTheAppsOwnConsole() throws Exception {
        Account owner = signup("owner");
        long id = createProject(owner, "logs");
        buildSite(owner, id);                                    // the agent's build gate
        post(project(id) + "/build", owner, null, 200);          // a manual build
        String url = post(project(id) + "/preview", owner, null, 200).path("url").asText();
        String token = url.split("/")[2];

        raw(url, null);
        raw("/p/" + token + "/missing.js", null);
        int reported = mvc.perform(MockMvcRequestBuilders
                        .post("/p/" + token + "/__log")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{\"level\":\"error\",\"message\":\"TypeError: x is undefined (app.js:3)\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(reported).isEqualTo(204);

        JsonNode lines = get(project(id) + "/preview/logs", owner, 200);
        StringBuilder all = new StringBuilder();
        lines.forEach(l -> all.append(l.path("source").asText()).append('|')
                .append(l.path("level").asText()).append('|').append(l.path("message").asText()).append('\n'));
        String log = all.toString();

        assertThat(log).contains("build|info|Build started");
        assertThat(log).contains("build|info|Build passed");
        assertThat(log).contains("preview|info|Preview started");
        assertThat(log).contains("http|info|GET /index.html 200");
        assertThat(log).contains("http|warn|GET /missing.js 404");
        assertThat(log).contains("console|error|TypeError: x is undefined (app.js:3)");

        // "after" returns only what is newer.
        long lastSeq = lines.get(lines.size() - 1).path("seq").asLong();
        assertThat(get(project(id) + "/preview/logs?after=" + lastSeq, owner, 200)).isEmpty();
    }

    @Test
    void aFailedBuildLogsEachProblem() {
        Account owner = signup("owner");
        long id = createProject(owner, "broken");
        llm.then(calls(write("index.html", INDEX)))          // links styles.css and app.js, writes neither
           .then(calls(finish("Done?")))
           .then(calls(write("styles.css", CSS), write("app.js", JS)))
           .then(calls(finish("Done.")));
        post(project(id) + "/generate", owner, Map.of("prompt", "go"), 200);

        StringBuilder log = new StringBuilder();
        get(project(id) + "/preview/logs", owner, 200)
                .forEach(l -> log.append(l.path("level").asText()).append('|').append(l.path("message").asText()).append('\n'));
        assertThat(log.toString())
                .contains("error|Build failed")
                .contains("error|index.html references a file that does not exist: styles.css")
                .contains("info|Build passed");
    }

    @Test
    void theConsoleEndpointOnlyOpensForALivePreview() throws Exception {
        Account owner = signup("owner");
        long id = createProject(owner, "dead-link");
        buildSite(owner, id);
        String token = post(project(id) + "/preview", owner, null, 200).path("url").asText().split("/")[2];
        call(HttpMethod.DELETE, project(id) + "/preview", owner, null, 204);

        int status = mvc.perform(MockMvcRequestBuilders
                        .post("/p/" + token + "/__log").contentType(MediaType.TEXT_PLAIN)
                        .content("{\"level\":\"error\",\"message\":\"forged\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(404);
        assertThat(raw("/p/" + token + "/index.html", null).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void theStreamReplaysTheBacklogThenFollowsLiveLines() throws Exception {
        Account owner = signup("owner");
        Account stranger = signup("stranger");
        long id = createProject(owner, "stream");
        buildSite(owner, id);

        assertThat(raw(project(id) + "/preview/logs/stream", stranger).getResponse().getStatus()).isEqualTo(404);

        MvcResult stream = raw(project(id) + "/preview/logs/stream", owner);
        assertThat(stream.getRequest().isAsyncStarted()).isTrue();
        assertThat(stream.getResponse().getContentAsString())
                .contains("event:log").contains("Build started").contains("Build passed");

        int before = count(stream.getResponse().getContentAsString(), "Build started");
        post(project(id) + "/build", owner, null, 200);       // happens while the stream is open
        assertThat(count(stream.getResponse().getContentAsString(), "Build started")).isEqualTo(before + 1);
    }

    @Test
    void aResumeIdFromAnotherInstanceStillGetsTheBacklog() throws Exception {
        Account owner = signup("owner");
        long id = createProject(owner, "resume");
        buildSite(owner, id);

        var req = MockMvcRequestBuilders.get(project(id) + "/preview/logs/stream")
                .header("Authorization", "Bearer " + owner.token())
                .header("Last-Event-ID", String.valueOf(Long.MAX_VALUE / 2));    // "seen" lines we never sent
        MvcResult stream = mvc.perform(req).andReturn();

        assertThat(stream.getResponse().getContentAsString()).contains("Build started");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
