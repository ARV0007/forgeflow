package com.forgeflow.intelligence.retrieval;

import com.forgeflow.billing.UsageKind;
import com.forgeflow.billing.UsageMeter;
import com.forgeflow.support.ApiTestSupport;
import com.forgeflow.workspace.ProjectFileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static com.forgeflow.support.ScriptedLlm.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The two knobs the retrieval eval turns: fusion weights, and an LLM rerank on top. */
class FusionAndRerankTest extends ApiTestSupport {

    @Autowired
    ProjectFileService files;
    @Autowired
    UsageMeter usage;

    private long project(Account a) {
        long id = createProject(a, "knobs");
        files.write(id, "cart.js", "function addToCart(item) { cart.push(item); }", a.id());
        files.write(id, "theme.js", "// Dark mode toggle\nfunction toggleDarkMode() { document.body.classList.toggle('dark'); }", a.id());
        files.write(id, "toast.js", "// small pop-up notices\nfunction showToast(message) { alert(message); }", a.id());
        return id;
    }

    private String search(long id, Account a, String params) {
        return "/api/v1/projects/" + id + "/search?" + params;
    }

    @Test
    void rrfAddsWeightedReciprocalRanks() {
        Map<Long, Double> plain = CodeIndex.fuse(List.of(1L, 2L), List.of(2L, 3L), CodeIndex.Fusion.EQUAL);
        assertThat(plain.get(2L)).isEqualTo(1.0 / 62 + 1.0 / 61);      // second by meaning, first by words
        assertThat(plain.get(2L)).isGreaterThan(plain.get(1L));          // agreement beats one first place

        Map<Long, Double> wordsMuted = CodeIndex.fuse(List.of(1L, 2L), List.of(2L, 3L), new CodeIndex.Fusion(1, 0));
        assertThat(wordsMuted.get(1L)).isGreaterThan(wordsMuted.get(2L)); // now it's vector order
        assertThat(wordsMuted.get(3L)).isZero();
    }

    @Test
    void weightsCanBeSetPerSearchAndAreValidated() {
        Account a = signup("weights");
        long id = project(a);
        JsonNode vectorOnly = get(search(id, a, "q=toggleDarkMode&mode=vector"), a, 200);
        JsonNode keywordMuted = get(search(id, a, "q=toggleDarkMode&keywordWeight=0"), a, 200);
        assertThat(keywordMuted.get(0).path("path").asText()).isEqualTo(vectorOnly.get(0).path("path").asText());

        get(search(id, a, "q=x&keywordWeight=-1"), a, 400);
        get(search(id, a, "q=x&vectorWeight=99"), a, 400);
    }

    @Test
    void rerankReordersByTheModelsAnswer() {
        Account a = signup("rerank");
        long id = project(a);
        JsonNode fused = get(search(id, a, "q=dark mode toggle&k=3"), a, 200);
        String fusedSecond = fused.get(1).path("path").asText();

        llm.then(text("[1, 0, 2]"));
        JsonNode reranked = get(search(id, a, "q=dark mode toggle&k=3&rerank=true"), a, 200);
        assertThat(reranked.get(0).path("path").asText()).isEqualTo(fusedSecond);
        assertThat(llm.seen().get(0).get(0).text()).startsWith("Question: dark mode toggle").contains("[0] ");
    }

    @Test
    void rerankIsMeteredLikeAnyModelCall() {
        Account a = signup("metered");
        long id = project(a);
        llm.then(text("[0, 1, 2]"));
        get(search(id, a, "q=dark mode toggle&k=3&rerank=true"), a, 200);
        assertThat(usage.used(a.id())).isEqualTo(120);           // ScriptedLlm reports 120 tokens a call

        usage.record(a.id(), id, UsageKind.AI_TOKENS, 10_000_000, "test: spend it all");
        get(search(id, a, "q=dark mode toggle&k=3&rerank=true"), a, 402);
        get(search(id, a, "q=dark mode toggle&k=3"), a, 200);       // plain search costs no tokens
    }

    @Test
    void aFailedRerankKeepsTheFusedOrderAndSaysSo() throws Exception {
        Account a = signup("rerank-down");
        long id = project(a);
        JsonNode fused = get(search(id, a, "q=dark mode toggle&k=3"), a, 200);

        // Nothing scripted: the model call throws, as an unreachable provider would.
        MvcResult r = mvc.perform(MockMvcRequestBuilders.get(search(id, a, "q=dark mode toggle&k=3&rerank=true"))
                .header("Authorization", "Bearer " + a.token())).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getHeader(SearchController.RERANK_FAILED_HEADER)).isEqualTo("true");
        assertThat(json.readTree(r.getResponse().getContentAsString()).get(0).path("path").asText())
                .isEqualTo(fused.get(0).path("path").asText());
    }

    @Test
    void sloppyRerankRepliesAreRepairedNotTrusted() {
        assertThat(LlmReranker.parseOrder("Sure! [2, 2, 9, 0]", 3)).containsExactly(2, 0, 1);   // dup + out of range dropped, 1 appended
        assertThat(LlmReranker.parseOrder("```json\n[1,0,2]\n```", 3)).containsExactly(1, 0, 2);
        assertThatThrownBy(() -> LlmReranker.parseOrder("I think the first one", 3)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> LlmReranker.parseOrder("[7, 8]", 3)).isInstanceOf(IllegalStateException.class);
    }
}
