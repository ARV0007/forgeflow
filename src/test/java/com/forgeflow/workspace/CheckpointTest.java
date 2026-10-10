package com.forgeflow.workspace;

import com.forgeflow.shared.llm.ToolCall;
import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static com.forgeflow.support.ScriptedLlm.CSS;
import static com.forgeflow.support.ScriptedLlm.INDEX;
import static com.forgeflow.support.ScriptedLlm.JS;
import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static com.forgeflow.support.ScriptedLlm.write;
import static org.assertj.core.api.Assertions.assertThat;

/** Version history: a checkpoint per AI run, diffs between them, and an undoable restore. */
class CheckpointTest extends ApiTestSupport {

    private static String cp(long id) {
        return "/api/v1/projects/" + id + "/checkpoints";
    }

    private void build(Account a, long id) {
        llm.then(calls(write("index.html", INDEX), write("styles.css", CSS), write("app.js", JS)))
           .then(calls(finish("Built.")));
        post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "Build a greeting page\nwith a button"), 200);
    }

    private void edit(Account a, long id, String prompt, ToolCall... changes) {
        llm.then(calls(changes)).then(calls(finish("Done.")));
        post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", prompt), 200);
    }

    private String file(Account a, long id, String path) {
        return get("/api/v1/projects/" + id + "/files/content?path=" + path, a, 200).path("content").asText();
    }

    @Test
    void everyRunThatChangesFilesIsACheckpointWithAReadableDiff() {
        Account a = signup("history");
        long id = createProject(a, "versions");
        build(a, id);
        edit(a, id, "Say goodbye instead",
                new ToolCall("edit_file", Map.of("path", "index.html", "old_text", "<h1>Hello</h1>", "new_text", "<h1>Goodbye</h1>")),
                write("about.html", "<h1>About</h1>"));

        JsonNode list = get(cp(id), a, 200);
        assertThat(list).hasSize(2);                                          // newest first
        assertThat(list.get(0).path("label").asText()).isEqualTo("Say goodbye instead");
        assertThat(list.get(0).path("kind").asText()).isEqualTo("RUN");
        assertThat(list.get(0).path("added").asInt()).isEqualTo(1);
        assertThat(list.get(0).path("changed").asInt()).isEqualTo(1);
        assertThat(list.get(1).path("label").asText()).isEqualTo("Build a greeting page");   // first line only
        assertThat(list.get(1).path("added").asInt()).isEqualTo(3);

        JsonNode diff = get(cp(id) + "/" + list.get(0).path("id").asLong() + "/diff", a, 200);
        assertThat(diff).hasSize(2);
        JsonNode about = diff.get(0);
        assertThat(about.path("path").asText()).isEqualTo("about.html");
        assertThat(about.path("status").asText()).isEqualTo("ADDED");
        JsonNode index = diff.get(1);
        assertThat(index.path("status").asText()).isEqualTo("MODIFIED");
        assertThat(index.path("diff").asText()).startsWith("@@ -1,2 +1,2 @@")
                .contains("-<body><h1>Hello</h1>").contains("+<body><h1>Goodbye</h1>");
        assertThat(index.path("additions").asInt()).isEqualTo(1);
    }

    @Test
    void restoreBringsBackTheOldFilesRemovesNewOnesAndCanItselfBeUndone() {
        Account a = signup("restore");
        long id = createProject(a, "time-travel");
        build(a, id);
        long first = get(cp(id), a, 200).get(0).path("id").asLong();
        edit(a, id, "Rename it", new ToolCall("edit_file",
                Map.of("path", "index.html", "old_text", "<h1>Hello</h1>", "new_text", "<h1>Renamed</h1>")),
                write("extra.js", "console.log('extra');"));
        long second = get(cp(id), a, 200).get(0).path("id").asLong();

        JsonNode restored = post(cp(id) + "/" + first + "/restore", a, null, 200);
        assertThat(restored.path("kind").asText()).isEqualTo("RESTORE");
        assertThat(restored.path("label").asText()).startsWith("Restored to #" + first);
        assertThat(file(a, id, "index.html")).contains("<h1>Hello</h1>");
        get("/api/v1/projects/" + id + "/files/content?path=extra.js", a, 404);
        // No "before restoring" checkpoint: the state being left was already the latest one.
        assertThat(get(cp(id), a, 200)).hasSize(3);

        post(cp(id) + "/" + second + "/restore", a, null, 200);                // undo the undo
        assertThat(file(a, id, "index.html")).contains("<h1>Renamed</h1>");
        assertThat(file(a, id, "extra.js")).contains("extra");
        // Old versions stay readable by checkpoint.
        assertThat(get(cp(id) + "/" + first + "/files/content?path=index.html", a, 200).path("content").asText())
                .contains("<h1>Hello</h1>");
    }

    @Test
    void handEditsAreProtectedByABaselineAndUnchangedStateIsNotAVersion() {
        Account a = signup("baseline");
        long id = createProject(a, "hand-made");
        call(HttpMethod.PUT, "/api/v1/projects/" + id + "/files/content", a,
                Map.of("path", "index.html", "content", "<h1>Mine</h1>"), 200);
        edit(a, id, "Make it theirs", write("index.html", "<h1>Theirs</h1>"));
        JsonNode list = get(cp(id), a, 200);
        assertThat(list).hasSize(2);
        assertThat(list.get(1).path("kind").asText()).isEqualTo("BASELINE");   // the hand-made version survives

        edit(a, id, "Change nothing", write("index.html", "<h1>Theirs</h1>"));
        assertThat(get(cp(id), a, 200)).hasSize(2);                             // same tree: no new version

        // Hand edit after the run, then restore: the hand edit is checkpointed before it's overwritten.
        call(HttpMethod.PUT, "/api/v1/projects/" + id + "/files/content", a,
                Map.of("path", "index.html", "content", "<h1>Edited by hand</h1>"), 200);
        post(cp(id) + "/" + list.get(1).path("id").asLong() + "/restore", a, null, 200);
        JsonNode after = get(cp(id), a, 200);
        assertThat(after.get(1).path("label").asText()).startsWith("Before restoring to #");
        assertThat(file(a, id, "index.html")).isEqualTo("<h1>Mine</h1>");
    }

    @Test
    void viewersMayLookOnlyEditorsMayRestoreAndCheckpointsDontCrossProjects() {
        Account owner = signup("owner");
        Account viewer = signup("viewer");
        Account stranger = signup("stranger");
        long id = createProject(owner, "guarded");
        long other = createProject(owner, "other");
        build(owner, id);
        build(owner, other);
        post("/api/v1/projects/" + id + "/members", owner, Map.of("email", viewer.email(), "role", "VIEWER"), 201);
        long cid = get(cp(id), owner, 200).get(0).path("id").asLong();
        long otherCid = get(cp(other), owner, 200).get(0).path("id").asLong();

        get(cp(id), viewer, 200);
        get(cp(id) + "/" + cid + "/diff", viewer, 200);
        post(cp(id) + "/" + cid + "/restore", viewer, null, 403);
        get(cp(id), stranger, 404);
        get(cp(id) + "/" + otherCid + "/diff", owner, 404);                    // right user, wrong project
        post(cp(id) + "/" + otherCid + "/restore", owner, null, 404);
    }
}
