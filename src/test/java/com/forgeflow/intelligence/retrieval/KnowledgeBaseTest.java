package com.forgeflow.intelligence.retrieval;

import com.forgeflow.shared.llm.LlmMessage;
import com.forgeflow.shared.llm.ToolCall;
import com.forgeflow.support.ApiTestSupport;
import com.forgeflow.workspace.Stack;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.forgeflow.support.ScriptedLlm.INDEX;
import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static com.forgeflow.support.ScriptedLlm.write;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The knowledge base: notes load and chunk by section, search finds the right
 * note, React advice never reaches a plain HTML project, and the agent gets
 * notes both up front and through search_docs.
 */
class KnowledgeBaseTest extends ApiTestSupport {

    @Autowired
    KnowledgeBase knowledge;

    @Test
    void notesChunkBySectionAndKeepTheirStacks() {
        List<KnowledgeBase.Chunk> chunks = KnowledgeBase.chunk("demo", """
                <!-- stacks: react -->
                # A title

                ## First thing
                Some words.

                ## Second thing
                More words.
                """);
        assertThat(chunks).extracting(KnowledgeBase.Chunk::section).containsExactly("First thing", "Second thing");
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.title()).isEqualTo("A title");
            assertThat(c.stacks()).isEqualTo("react");
        });
        assertThat(chunks.get(1).content()).isEqualTo("More words.");
        assertThat(KnowledgeBase.chunk("plain", "# T\n## S\nx").get(0).stacks()).isEqualTo("static,react");
    }

    @Test
    void everyNoteLoadsAndLoadingTwiceChangesNothing() {
        List<Map<String, Object>> notes = knowledge.list();
        assertThat(notes).hasSize(13);
        assertThat(notes).allSatisfy(n -> assertThat(((Number) n.get("sections")).intValue()).isGreaterThanOrEqualTo(4));
        knowledge.ensureLoaded();
        assertThat(knowledge.list()).isEqualTo(notes);
    }

    /**
     * A small benchmark, run offline: each question names the note that should
     * answer it. With the offline embedder this is mostly the keyword half
     * (the live run with real embeddings is the eval's job), so the questions
     * share some words with their notes - this guards the plumbing and the
     * corpus, not the quality ceiling.
     */
    @Test
    void searchFindsTheRightNote() {
        Map<String, String> questions = new java.util.LinkedHashMap<>();
        questions.put("useEffect runs on every render and loops forever", "react-state-and-effects");
        questions.put("which key should a list item use when items reorder", "react-lists-and-keys");
        questions.put("createRoot in main.jsx and the index.html entry script", "react-vite-project");
        questions.put("controlled input with onChange and a checkbox", "react-forms");
        questions.put("center a div both ways", "css-layout");
        questions.put("card grid with auto-fill and minmax", "css-layout");
        questions.put("viewport meta tag and mobile media queries", "responsive-design");
        questions.put("dark mode toggle without a flash of the wrong theme", "dark-mode-and-theming");
        questions.put("label for an input and alt text for images", "accessibility");
        questions.put("querySelector returns null because the script runs too early", "dom-events");
        questions.put("save todos to localStorage as JSON", "local-storage");
        questions.put("fetch does not reject on a 404, check res.ok", "fetch-and-async");
        questions.put("debounce a search as you type", "fetch-and-async");
        questions.put("prefers-reduced-motion and animating transform", "animation-and-motion");
        questions.put("countdown with setInterval drifts, use Date.now", "timers-numbers-dates");
        questions.put("format money as currency with Intl.NumberFormat", "timers-numbers-dates");

        List<String> misses = new ArrayList<>();
        for (Map.Entry<String, String> q : questions.entrySet()) {
            List<String> top = knowledge.search(q.getKey(), 3, null).stream().map(KnowledgeBase.Hit::doc).toList();
            if (!top.contains(q.getValue())) {
                misses.add(q.getKey() + " -> " + top);
            }
        }
        assertThat(misses).as("questions whose note wasn't in the top 3").isEmpty();
    }

    @Test
    void aPlainHtmlProjectIsNeverHandedReactAdvice() {
        List<KnowledgeBase.Hit> forStatic = knowledge.search("list keys useState useEffect createRoot", 10, Stack.STATIC);
        assertThat(forStatic).extracting(KnowledgeBase.Hit::doc).noneMatch(d -> d.startsWith("react-"));
        List<KnowledgeBase.Hit> forReact = knowledge.search("list keys useState useEffect createRoot", 10, Stack.REACT);
        assertThat(forReact).extracting(KnowledgeBase.Hit::doc).anyMatch(d -> d.startsWith("react-"));
        assertThat(knowledge.search("addEventListener event delegation", 10, Stack.REACT))
                .extracting(KnowledgeBase.Hit::doc).doesNotContain("dom-events");
    }

    @Test
    void aRunStartsWithNotesAndCanSearchForMore() {
        Account a = signup("notes");
        long id = post("/api/v1/projects", a, Map.of("name", "react-notes", "stack", "REACT"), 201).path("id").asLong();
        llm.then(calls(new ToolCall("search_docs", Map.of("query", "useEffect cleanup clearInterval"))))
           .then(calls(finish("Looked it up.")));
        int before = llm.seen().size();

        JsonNode run = post("/api/v1/projects/" + id + "/generate", a,
                Map.of("prompt", "a countdown timer component that clears its interval"), 200);

        // Up front: the request carried reference notes for a React project.
        String request = llm.seen().get(before).get(0).text();
        assertThat(request).contains("REFERENCE NOTES").contains("›");
        // On demand: search_docs answered with a note section.
        List<LlmMessage> afterSearch = llm.seen().get(before + 1);
        String toolResult = afterSearch.get(afterSearch.size() - 1).toolResults().get(0).output();
        assertThat(toolResult).contains("Cleaning up").contains("clearInterval");
        assertThat(run.path("toolUsage").path("search_docs").asInt()).isEqualTo(1);
    }

    @Test
    void notesCanBeSwitchedOffPerCall() {
        assertThat(knowledge.notesFor("dark mode", Stack.STATIC, 0)).isEmpty();
        assertThat(knowledge.notesFor("dark mode toggle flash", Stack.STATIC, 2)).hasValueSatisfying(n -> {
            assertThat(n.hits()).hasSizeLessThanOrEqualTo(2);
            assertThat(n.text()).contains("Dark mode and theming");
        });
    }

    @Test
    void theApiListsAndSearches() {
        Account a = signup("notes-api");
        assertThat(get("/api/v1/knowledge", a, 200)).hasSize(13);
        JsonNode hits = get("/api/v1/knowledge/search?q=useEffect%20cleanup&stack=react", a, 200);
        assertThat(hits.get(0).path("doc").asText()).isEqualTo("react-state-and-effects");
        assertThat(hits.get(0).path("source").asText()).startsWith("react-state-and-effects ›");
        get("/api/v1/knowledge/search?q=x&stack=cobol", a, 400);
    }
}
