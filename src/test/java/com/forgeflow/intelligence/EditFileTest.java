package com.forgeflow.intelligence;

import com.forgeflow.shared.llm.LlmMessage;
import com.forgeflow.shared.llm.ToolCall;
import com.forgeflow.shared.llm.ToolResult;
import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static com.forgeflow.support.ScriptedLlm.CSS;
import static com.forgeflow.support.ScriptedLlm.INDEX;
import static com.forgeflow.support.ScriptedLlm.JS;
import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static com.forgeflow.support.ScriptedLlm.write;
import static org.assertj.core.api.Assertions.assertThat;

/** edit_file: exact, unique, and every refusal teaches the model what to do instead. */
class EditFileTest extends ApiTestSupport {

    private static ToolCall edit(String path, String oldText, String newText) {
        return new ToolCall("edit_file", Map.of("path", path, "old_text", oldText, "new_text", newText));
    }

    private long site(Account a) {
        long id = createProject(a, "edits");
        llm.then(calls(write("index.html", INDEX), write("styles.css", CSS + "\nh2 { color: navy; }"),
                        write("app.js", JS)))
           .then(calls(finish("Built.")));
        post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "build"), 200);
        return id;
    }

    private String content(Account a, long id, String path) {
        return get("/api/v1/projects/" + id + "/files/content?path=" + path, a, 200).path("content").asText();
    }

    /** The tool result the model saw for the first call of the most recent run's first round. */
    private ToolResult lastResult(int seenIndex) {
        List<LlmMessage> h = llm.seen().get(seenIndex);
        return h.get(h.size() - 1).toolResults().get(0);
    }

    @Test
    void aUniqueMatchIsReplacedAndNothingElseMoves() {
        Account a = signup("editor");
        long id = site(a);

        llm.then(calls(edit("index.html", "<h1>Hello</h1>", "<h1>Hello, edited</h1>")))
           .then(calls(finish("Changed the heading.")));
        JsonNode run = post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "change heading"), 200);

        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(run.path("filesWritten")).extracting(JsonNode::asText).containsExactly("index.html");
        assertThat(content(a, id, "index.html")).isEqualTo(INDEX.replace("<h1>Hello</h1>", "<h1>Hello, edited</h1>"));
        assertThat(lastResult(3).output()).startsWith("Edited index.html");

        JsonNode index = null;
        for (JsonNode f : get("/api/v1/projects/" + id + "/files", a, 200)) {
            if (f.path("path").asText().equals("index.html")) {
                index = f;
            }
        }
        assertThat(index.path("version").asInt()).isEqualTo(2);
    }

    @Test
    void textThatIsNotThereIsRefusedWithAHint() {
        Account a = signup("missing");
        long id = site(a);

        llm.then(calls(edit("index.html", "<h1>Goodbye</h1>", "x"))).then(calls(finish("Tried.")));
        post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "edit"), 200);

        ToolResult r = lastResult(3);
        assertThat(r.ok()).isFalse();
        assertThat(r.output()).contains("not found").contains("read_file");
        assertThat(content(a, id, "index.html")).isEqualTo(INDEX);
    }

    @Test
    void ambiguousTextIsRefusedWithTheCount() {
        Account a = signup("ambiguous");
        long id = site(a);

        llm.then(calls(edit("styles.css", "color: navy;", "color: red;"))).then(calls(finish("Tried.")));
        post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "edit"), 200);

        ToolResult r = lastResult(3);
        assertThat(r.ok()).isFalse();
        assertThat(r.output()).contains("appears 2 times").contains("surrounding lines");
        assertThat(content(a, id, "styles.css")).doesNotContain("red");
    }

    @Test
    void editingAFileThatDoesNotExistPointsAtWriteFile() {
        Account a = signup("nofile");
        long id = site(a);

        llm.then(calls(edit("about.html", "a", "b"))).then(calls(finish("Tried.")));
        post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "edit"), 200);

        assertThat(lastResult(3).output()).contains("No such file: about.html").contains("write_file");
    }

    /** Twelve rules, so the file is big enough for the rewrite note to apply. */
    private static final String THEME = """
            body { margin: 0; }
            h1 { font-size: 2rem; }
            h2 { font-size: 1.5rem; }
            p { line-height: 1.5; }
            a { color: teal; }
            ul { padding: 0; }
            li { list-style: none; }
            input { padding: 0.5rem; }
            label { display: block; }
            .card { border-radius: 8px; }
            .muted { color: #777; }
            button { background: blue; }
            """;

    @Test
    void rewritingAFileToChangeOneLineEarnsANudgeTowardEditFile() {
        Account a = signup("rewriter");
        long id = site(a);

        llm.then(calls(write("theme.css", THEME)))                                  // new file: no note
           .then(calls(write("theme.css", THEME.replace("blue", "green"))))      // one line changed: note
           .then(calls(finish("Made the button green.")));
        post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "green button"), 200);

        assertThat(lastResult(3).output()).isEqualTo("Wrote theme.css (" + THEME.length() + " bytes)");
        ToolResult rewrite = lastResult(4);
        assertThat(rewrite.ok()).isTrue();                       // the write still happened
        assertThat(rewrite.output()).contains("most of this file was unchanged").contains("edit_file");
        assertThat(content(a, id, "theme.css")).contains("background: green");
    }

    @Test
    void theNoteOnlyFiresWhenMostOfABigEnoughFileSurvives() {
        String twelve = THEME;
        String oneChanged = THEME.replace("blue", "green");
        String halfChanged = THEME.replace("rem", "em").replace("8px", "4px").replace("teal", "red");

        assertThat(AgentTools.mostlyUnchanged(twelve, oneChanged)).isTrue();
        assertThat(AgentTools.mostlyUnchanged(twelve, twelve + "footer { color: grey; }\n")).isTrue();
        assertThat(AgentTools.mostlyUnchanged(twelve, halfChanged)).isFalse();
        assertThat(AgentTools.mostlyUnchanged(twelve, "body { margin: 0; }")).isFalse();      // most of it dropped
        assertThat(AgentTools.mostlyUnchanged("a\nb\nc", "a\nb\nc\nd")).isFalse();             // too small to matter
        // A repeated line has to survive as many times as it appeared.
        String repeated = "x {}\n".repeat(10);
        assertThat(AgentTools.mostlyUnchanged(repeated, "x {}\n".repeat(2))).isFalse();
    }

    @Test
    void anEditThatBreaksTheBuildGoesThroughTheRepairLoop() {
        Account a = signup("breaker");
        long id = site(a);

        llm.then(calls(edit("app.js", "alert('hi')", "alert('hi'")))      // unbalanced
           .then(calls(finish("Done.")))                                    // refused by the gate
           .then(calls(edit("app.js", "alert('hi'", "alert('hi')")))        // the repair
           .then(calls(finish("Done, and fixed.")));
        JsonNode run = post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "tweak"), 200);

        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(run.path("repairRounds").asInt()).isEqualTo(1);
        assertThat(content(a, id, "app.js")).isEqualTo(JS);
    }
}
