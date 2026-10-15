package com.forgeflow.intelligence.retrieval;

import com.forgeflow.billing.UsageKind;
import com.forgeflow.billing.UsageMeter;
import com.forgeflow.shared.llm.LlmMessage;
import com.forgeflow.support.ApiTestSupport;
import com.forgeflow.workspace.ProjectFileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static com.forgeflow.support.ScriptedLlm.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ask your project: answers come only from the project's code, cite real
 * sources, and say so when the code doesn't hold the answer.
 */
class AskProjectTest extends ApiTestSupport {

    @Autowired
    ProjectFileService files;
    @Autowired
    UsageMeter usage;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    private long smallProject(Account a) {
        long id = createProject(a, "ask-me");
        files.write(id, "index.html", "<!doctype html><html><body><button id=\"theme\">Dark</button>"
                + "<script src=\"theme.js\"></script></body></html>", a.id());
        files.write(id, "theme.js", "// Dark mode\nfunction toggleDarkMode() {\n  document.body.classList.toggle('dark');\n}\n"
                + "document.getElementById('theme').onclick = toggleDarkMode;", a.id());
        return id;
    }

    private JsonNode ask(long id, Account a, String q, int status) {
        return post("/api/v1/projects/" + id + "/ask", a, Map.of("question", q), status);
    }

    @Test
    void anAnswerCitesRealSourcesAndDropsInventedOnes() {
        Account a = signup("ask");
        long id = smallProject(a);
        // Small project: every file goes in whole, sorted - [1] index.html, [2] theme.js.
        llm.then(text("Dark mode is toggled by toggleDarkMode, which flips the dark class on the body [2]. "
                + "The button that triggers it is in the page [1, 9]. Also see [7]."));

        JsonNode r = ask(id, a, "How does dark mode work?", 200);

        assertThat(r.path("found").asBoolean()).isTrue();
        assertThat(r.path("sources").asInt()).isEqualTo(2);
        // [9] and [7] name no source: removed from the answer and from the citations.
        assertThat(r.path("answer").asText()).contains("[2]").contains("[1]").doesNotContain("9").doesNotContain("[7]");
        assertThat(r.path("citations")).hasSize(2);
        assertThat(r.path("citations").get(0).path("path").asText()).isEqualTo("theme.js");
        assertThat(r.path("citations").get(0).path("startLine").asInt()).isEqualTo(1);
        assertThat(r.path("citations").get(1).path("path").asText()).isEqualTo("index.html");

        // What the model was shown: numbered sources with line numbers, then the question.
        List<LlmMessage> seen = llm.seen().get(llm.seen().size() - 1);
        String shown = seen.get(0).text();
        assertThat(shown).contains("[2] theme.js (lines 1-5)").contains("2: function toggleDarkMode() {")
                .contains("QUESTION\nHow does dark mode work?");
        assertThat(llm.systemPrompts().get(llm.systemPrompts().size() - 1)).contains("NOT_FOUND").contains("ONLY");
    }

    @Test
    void notFoundIsARealAnswerNotAGuess() {
        Account a = signup("ask-nf");
        long id = smallProject(a);
        llm.then(text("NOT_FOUND: there is no payment code in these files."));

        JsonNode r = ask(id, a, "How are payments processed?", 200);

        assertThat(r.path("found").asBoolean()).isFalse();
        assertThat(r.path("citations")).isEmpty();
        assertThat(r.path("answer").asText()).startsWith("I couldn't find that in this project's code.")
                .contains("no payment code");
    }

    @Test
    void aBigProjectIsSearchedNotSentWhole() {
        Account a = signup("ask-big");
        long id = createProject(a, "big");
        for (int i = 0; i < 18; i++) {
            files.write(id, "js/module" + i + ".js", "// helper number " + i + "\nfunction helper" + i + "() { return " + i + "; }", a.id());
        }
        files.write(id, "js/promo.js", "// Promo codes\nfunction applyPromoCode(code) {\n  return code === 'SAVE10' ? 0.1 : 0;\n}", a.id());
        llm.then(text("applyPromoCode gives 10% off for SAVE10 [1]."));

        JsonNode r = ask(id, a, "applyPromoCode discount", 200);

        assertThat(r.path("sources").asInt()).isLessThanOrEqualTo(ProjectQa.TOP_K);
        String shown = llm.seen().get(llm.seen().size() - 1).get(0).text();
        assertThat(shown).contains("[1] js/promo.js");
        assertThat(r.path("citations").get(0).path("path").asText()).isEqualTo("js/promo.js");
    }

    @Test
    void viewersMayAskStrangersMayNotAndTheAskerPays() {
        Account owner = signup("ask-owner");
        Account viewer = signup("ask-viewer");
        Account stranger = signup("ask-stranger");
        long id = smallProject(owner);
        post("/api/v1/projects/" + id + "/members", owner, Map.of("email", viewer.email(), "role", "VIEWER"), 201);

        llm.then(text("It toggles a class [2]."));
        ask(id, viewer, "What does the button do?", 200);
        ask(id, stranger, "What does the button do?", 404);
        ask(id, owner, "   ", 400);

        Long charged = jdbc.queryForObject(
                "SELECT coalesce(sum(quantity), 0) FROM usage_logs WHERE user_id = ? AND kind = 'AI_TOKENS'", Long.class, viewer.id());
        assertThat(charged).isEqualTo(120L);

        // Out of tokens: 402, and the model is never called.
        usage.record(viewer.id(), id, UsageKind.AI_TOKENS, 10_000_000, "test: spend it all");
        int calls = llm.seen().size();
        ask(id, viewer, "What does the button do?", 402);
        assertThat(llm.seen()).hasSize(calls);
    }
}
