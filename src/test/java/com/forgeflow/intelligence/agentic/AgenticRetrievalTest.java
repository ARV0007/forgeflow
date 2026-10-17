package com.forgeflow.intelligence.agentic;

import com.forgeflow.shared.llm.ToolCall;
import com.forgeflow.support.ApiTestSupport;
import com.forgeflow.workspace.ProjectFileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static com.forgeflow.support.ScriptedLlm.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Corrective RAG: rewrite the question into queries, search with all of them,
 * grade what came back, retry once if too little helps. The threshold is set
 * to 2 files so these small projects are searched, not sent whole, and each
 * round grades 2 results so a five-file project still has something new for a
 * second round to find.
 */
@TestPropertySource(properties = {"forgeflow.retrieval.agentic.ask=true", "forgeflow.retrieval.whole-project-threshold=2",
        "forgeflow.retrieval.agentic.candidates=2"})
class AgenticRetrievalTest extends ApiTestSupport {

    @Autowired
    ProjectFileService files;

    private long shop(Account a) {
        long id = createProject(a, "shop");
        files.write(id, "index.html", "<!doctype html><html><body><h1>Shop</h1><div id=\"cart\"></div>"
                + "<script src=\"cart.js\"></script></body></html>", a.id());
        files.write(id, "cart.js", """
                function applyPromoCode(total, code) {
                  if (code === 'SAVE10') return total * 0.9;
                  return total;
                }
                function cartTotal(items) { return items.reduce((s, i) => s + i.price * i.qty, 0); }
                """, a.id());
        files.write(id, "theme.css", "body { background: #fff; } .dark { background: #111; color: #eee; }", a.id());
        files.write(id, "footer.js", "document.querySelector('footer').textContent = '(c) Shop';", a.id());
        files.write(id, "stock.js", "const MAX_QTY = 5; function canAdd(item) { return item.qty < MAX_QTY; }", a.id());
        return id;
    }

    private String asked(int call) {
        return llm.seen().get(call).get(0).text();
    }

    @Test
    void askRewritesTheQuestionGradesTheResultsAndAnswersFromWhatWasKept() {
        Account a = signup("crag");
        long id = shop(a);
        llm.then(text("{\"queries\": [\"applyPromoCode\", \"discount code total\"]}"))
           .then(text("{\"relevant\": [1, 2]}"))
           .then(text("The promo code takes 10% off in applyPromoCode [1]."));

        JsonNode r = post("/api/v1/projects/" + id + "/ask", a, Map.of("question", "where does the discount get applied?"), 200);

        assertThat(r.path("found").asBoolean()).isTrue();
        assertThat(llm.systemPrompts().get(0)).startsWith("You write search queries");
        assertThat(llm.systemPrompts().get(1)).startsWith("You judge search results");
        assertThat(asked(1)).contains("QUESTION\nwhere does the discount get applied?").contains("[1] ");
        // The answer saw exactly what the grader kept.
        assertThat(asked(2)).contains("[2] ").doesNotContain("\n[3] ");
        assertThat(r.path("sources").asInt()).isEqualTo(2);
        assertThat(r.path("searches").get(0).asText())
                .startsWith("searched 'where does the discount get applied?', 'applyPromoCode', 'discount code total' - 2 of ");
        assertThat(r.path("tokens").asInt()).isEqualTo(360);                 // three calls of 120
    }

    @Test
    void whenTooLittleIsRelevantItTriesNewQueriesWithWhatItLearned() {
        Account a = signup("crag-retry");
        long id = shop(a);
        llm.then(text("{\"queries\": [\"order limit\"]}"))
           .then(text("{\"relevant\": []}"))
           .then(text("{\"queries\": [\"MAX_QTY\", \"canAdd\"]}"))
           .then(text("{\"relevant\": [1]}"))
           .then(text("canAdd stops at MAX_QTY, which is 5 [1]."));

        JsonNode r = post("/api/v1/projects/" + id + "/ask", a, Map.of("question", "how many of one thing can I order?"), 200);

        assertThat(r.path("found").asBoolean()).isTrue();
        assertThat(asked(2)).contains("found little that helps").contains("order limit").contains("Try different angles");
        assertThat(r.path("searches")).hasSize(2);
        assertThat(r.path("searches").get(0).asText()).contains("0 of");
        assertThat(r.path("searches").get(1).asText()).contains("'MAX_QTY', 'canAdd' - 1 of");
        assertThat(r.path("sources").asInt()).isEqualTo(1);
    }

    @Test
    void whenNothingIsRelevantAskSaysSoWithoutAskingTheModelToAnswer() {
        Account a = signup("crag-none");
        long id = shop(a);
        llm.then(text("{\"queries\": [\"login form\"]}")).then(text("{\"relevant\": []}"))
           .then(text("{\"queries\": [\"password\", \"signIn\"]}")).then(text("{\"relevant\": []}"));

        JsonNode r = post("/api/v1/projects/" + id + "/ask", a, Map.of("question", "how does login work?"), 200);

        assertThat(r.path("found").asBoolean()).isFalse();
        assertThat(r.path("answer").asText()).contains("couldn't find that");
        assertThat(llm.seen()).hasSize(4);                                    // no answer call on nothing
    }

    @Test
    void ifGradingFailsTheSearchOrderIsKeptAndSaidSo() {
        Account a = signup("crag-ungraded");
        long id = shop(a);
        llm.then(text("{\"queries\": [\"cartTotal\"]}"))
           .then(text("Sure, the first few look good."))
           .then(text("cartTotal adds price times quantity [1]."));

        JsonNode r = post("/api/v1/projects/" + id + "/ask", a, Map.of("question", "how is the total worked out?"), 200);

        assertThat(r.path("found").asBoolean()).isTrue();
        assertThat(r.path("searches").get(0).asText()).contains("couldn't grade the results, kept the search order");
        assertThat(r.path("sources").asInt()).isEqualTo(2);                   // round 1's results, in fused order
    }

    @Test
    void theAgentCanAskForAThoroughSearchAndItsCostCountsTowardTheRun() {
        Account a = signup("crag-agent");
        long id = shop(a);
        llm.then(calls(new ToolCall("search_code", Map.of("query", "order limit", "thorough", true))))
           .then(text("{\"queries\": [\"MAX_QTY\"]}"))
           .then(text("{\"relevant\": [1, 2]}"))
           .then(calls(finish("The limit is MAX_QTY in stock.js.")));

        JsonNode r = post("/api/v1/projects/" + id + "/generate", a,
                Map.of("prompt", "where is the order limit?", "review", false), 200);

        assertThat(r.path("status").asText()).isEqualTo("SUCCEEDED");
        var toolResult = llm.seen().get(3).get(llm.seen().get(3).size() - 1).toolResults().get(0).output();
        assertThat(toolResult).startsWith("--- ");
        assertThat(r.path("agent").path("searches").get(0).asText()).startsWith("'order limit': searched 'order limit', 'MAX_QTY'");
        assertThat(r.path("totalTokens").asInt()).isEqualTo(4 * 120);         // the search's two calls included
    }

    @Test
    void theSearchApiRunsTheLoopForTheBenchmarkAndSaysWhatItTried() throws Exception {
        Account a = signup("crag-api");
        long id = shop(a);
        llm.then(text("{\"queries\": [\"applyPromoCode\"]}")).then(text("{\"relevant\": [1, 2]}"));

        MvcResult res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/projects/" + id + "/search?q=discount&mode=agentic&k=5")
                .header("Authorization", "Bearer " + a.token())).andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(res.getResponse().getContentAsString())).hasSize(2);
        assertThat(res.getResponse().getHeader("X-Search-Trace")).startsWith("searched 'discount', 'applyPromoCode' - 2 of");
    }
}
