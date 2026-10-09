package com.forgeflow.intelligence.retrieval;

import com.forgeflow.shared.llm.Embedder;
import com.forgeflow.shared.llm.LlmException;
import com.forgeflow.shared.llm.LlmMessage;
import com.forgeflow.shared.llm.ToolCall;
import com.forgeflow.shared.llm.ToolResult;
import com.forgeflow.shared.tracing.SpanReporter;
import com.forgeflow.shared.tracing.Tracer;
import com.forgeflow.support.ApiTestSupport;
import com.forgeflow.workspace.ProjectFileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.forgeflow.support.ScriptedLlm.CSS;
import static com.forgeflow.support.ScriptedLlm.INDEX;
import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static com.forgeflow.support.ScriptedLlm.write;
import static org.assertj.core.api.Assertions.assertThat;

/** RAG end to end, against real pgvector, with the offline hashing embedder. */
class RetrievalTest extends ApiTestSupport {

    private static final String APP_JS = """
            const todos = [];

            function renderTodos(list) {
              const ul = document.querySelector('#todos');
              ul.innerHTML = list.map(t => '<li>' + t + '</li>').join('');
            }

            document.querySelector('h1').addEventListener('click', () => renderTodos(todos));
            """;
    private static final String THEME_JS = """
            // Dark mode toggle: flips a class on the body and remembers nothing yet.
            function toggleDarkMode() {
              document.body.classList.toggle('dark');
            }
            """;

    @Autowired
    CodeIndex index;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ProjectFileService files;
    @Autowired
    TransactionTemplate tx;

    private long siteWithTwoScripts(Account a) {
        long id = createProject(a, "rag");
        llm.then(calls(write("index.html", INDEX), write("styles.css", CSS),
                        write("app.js", APP_JS), write("theme.js", THEME_JS)))
           .then(calls(finish("Built.")));
        post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "todo app"), 200);
        return id;
    }

    @Test
    void aRunIsIndexedAndExactIdentifiersAreFound() {
        Account a = signup("rag");
        long id = siteWithTwoScripts(a);

        // Indexed by the CodeGenerated listener (sync in tests), not by the search.
        assertThat(index.chunkCount(id)).isGreaterThanOrEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_chunks WHERE project_id = ? AND embedding IS NULL",
                Integer.class, id)).isZero();

        JsonNode hits = get("/api/v1/projects/" + id + "/search?q=renderTodos", a, 200);
        assertThat(hits.get(0).path("path").asText()).isEqualTo("app.js");
        assertThat(hits.get(0).path("content").asText()).contains("function renderTodos");
        assertThat(hits.get(0).path("startLine").asInt()).isEqualTo(1);

        JsonNode dark = get("/api/v1/projects/" + id + "/search?q=dark mode toggle", a, 200);
        assertThat(dark.get(0).path("path").asText()).isEqualTo("theme.js");
    }

    @Test
    void searchIsARead() {
        Account owner = signup("owner");
        Account stranger = signup("stranger");
        long id = siteWithTwoScripts(owner);
        get("/api/v1/projects/" + id + "/search?q=todos", stranger, 404);
    }

    @Test
    void reindexingTouchesOnlyWhatChanged() {
        Account a = signup("incremental");
        long id = siteWithTwoScripts(a);

        assertThat(index.ensureIndexed(id)).isEqualTo(new CodeIndex.IndexReport(0, 0, 0, 4));

        files.write(id, "theme.js", THEME_JS.replace("remembers nothing yet", "saves to localStorage"), a.id());
        CodeIndex.IndexReport r = index.ensureIndexed(id);
        assertThat(r.filesIndexed()).isEqualTo(1);
        assertThat(r.filesUnchanged()).isEqualTo(3);

        jdbc.update("DELETE FROM project_files WHERE project_id = ? AND path = 'theme.js'", id);
        assertThat(index.ensureIndexed(id).filesRemoved()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_chunks WHERE project_id = ? AND file_path = 'theme.js'",
                Integer.class, id)).isZero();
    }

    @Test
    void searchNeverCrossesProjects() {
        Account a = signup("a");
        Account b = signup("b");
        long mine = siteWithTwoScripts(a);
        siteWithTwoScripts(b);
        for (JsonNode h : get("/api/v1/projects/" + mine + "/search?q=renderTodos&k=20", a, 200)) {
            assertThat(h.path("path").asText()).isIn("index.html", "styles.css", "app.js", "theme.js");
        }
        assertThat(index.search(mine, "renderTodos", 20)).hasSizeLessThanOrEqualTo(4 + 2);
    }

    @Test
    void theAgentCanSearchItsOwnCode() {
        Account a = signup("searcher");
        long id = siteWithTwoScripts(a);

        llm.then(calls(new ToolCall("search_code", Map.of("query", "where is the dark mode toggle"))))
           .then(calls(finish("Found it in theme.js.")));
        post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "where is dark mode?"), 200);

        List<LlmMessage> afterSearch = llm.seen().get(3);     // 0-1 were the first run
        ToolResult result = afterSearch.get(afterSearch.size() - 1).toolResults().get(0);
        assertThat(result.toolName()).isEqualTo("search_code");
        assertThat(result.ok()).isTrue();
        assertThat(result.output()).startsWith("--- theme.js lines 1-").contains("toggleDarkMode");
    }

    @Test
    void aLargeProjectsRequestArrivesWithRetrievedCode() {
        Account a = signup("big");
        long id = createProject(a, "big");
        List<ToolCall> writes = new ArrayList<>(List.of(write("index.html", INDEX), write("styles.css", CSS),
                write("app.js", APP_JS), write("theme.js", THEME_JS)));
        for (int i = 1; i <= 13; i++) {
            writes.add(write("feature" + i + ".js", "function feature" + i + "() { return " + i + "; }"));
        }
        llm.then(calls(writes.toArray(ToolCall[]::new))).then(calls(finish("Built 17 files.")));
        post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "build"), 200);

        llm.then(calls(finish("Done.")));
        post("/api/v1/projects/" + id + "/generate", a,
                Map.of("prompt", "make the dark mode toggle remember its state"), 200);

        String firstMessage = llm.seen().get(2).get(0).text();
        assertThat(firstMessage).startsWith("make the dark mode toggle remember its state")
                .contains("RELEVANT CODE").contains("--- theme.js lines");
    }

    @Test
    void aSmallProjectsRequestIsLeftAlone() {
        Account a = signup("small");
        long id = siteWithTwoScripts(a);
        llm.then(calls(finish("Done.")));
        post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "tweak the toggle"), 200);
        assertThat(llm.seen().get(2).get(0).text()).isEqualTo("tweak the toggle");
    }

    @Test
    void withTheEmbedderDownSearchFallsBackToKeywords() {
        Account a = signup("offline");
        long id = siteWithTwoScripts(a);
        jdbc.update("DELETE FROM file_chunks WHERE project_id = ?", id);

        Embedder broken = new Embedder() {
            @Override
            public List<float[]> embed(List<String> texts, Kind kind) {
                throw new LlmException("quota exhausted");
            }

            @Override
            public String modelName() {
                return "broken";
            }
        };
        CodeIndex degraded = new CodeIndex(files, broken, jdbc, tx, new Tracer(SpanReporter.NOOP), 15, 8);

        List<CodeIndex.SearchHit> hits = degraded.search(id, "renderTodos", 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).path()).isEqualTo("app.js");
        // Stored without vectors - and flagged, so the next healthy pass fills them in.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_chunks WHERE project_id = ? AND embedding IS NOT NULL",
                Integer.class, id)).isZero();
        assertThat(index.ensureIndexed(id).filesIndexed()).isEqualTo(4);
    }
}
