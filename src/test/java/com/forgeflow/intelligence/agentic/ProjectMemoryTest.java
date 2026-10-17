package com.forgeflow.intelligence.agentic;

import com.forgeflow.shared.llm.ToolCall;
import com.forgeflow.support.ApiTestSupport;
import com.forgeflow.workspace.ProjectFileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static com.forgeflow.support.ScriptedLlm.CSS;
import static com.forgeflow.support.ScriptedLlm.INDEX;
import static com.forgeflow.support.ScriptedLlm.JS;
import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static org.assertj.core.api.Assertions.assertThat;

/** Project memory: facts that outlive a chat, saved by the agent or a person, read by every later run. */
class ProjectMemoryTest extends ApiTestSupport {

    @Autowired
    ProjectFileService files;

    static ToolCall remember(String fact) {
        return new ToolCall("remember", Map.of("fact", fact));
    }

    private long site(Account a) {
        long id = createProject(a, "memory");
        files.write(id, "index.html", INDEX, a.id());
        files.write(id, "styles.css", CSS, a.id());
        files.write(id, "app.js", JS, a.id());
        return id;
    }

    private String mem(long id) {
        return "/api/v1/projects/" + id + "/memories";
    }

    @Test
    void whatTheAgentRemembersInOneChatReachesTheNextRunEvenInANewChat() {
        Account a = signup("memory");
        long id = site(a);
        llm.then(calls(remember("Prices are shown in Indian rupees (₹)"), remember("Brand colour is #2f6f4f")))
           .then(calls(finish("Noted both.")));
        JsonNode first = post("/api/v1/projects/" + id + "/generate", a,
                Map.of("prompt", "From now on prices are in rupees, and our brand colour is #2f6f4f"), 200);
        assertThat(first.path("agent").path("remembered").toString()).contains("Indian rupees").contains("#2f6f4f");

        JsonNode facts = get(mem(id), a, 200);
        assertThat(facts).hasSize(2);
        assertThat(facts.get(0).path("source").asText()).isEqualTo("agent");
        assertThat(facts.get(0).path("runId").asLong()).isEqualTo(first.path("runId").asLong());

        // A later, unrelated request - the facts travel with it.
        llm.reset();
        llm.then(calls(finish("Added the price table.")));
        long session = post("/api/v1/projects/" + id + "/chat/sessions", a, Map.of(), 201).path("id").asLong();
        post("/api/v1/projects/" + id + "/chat/sessions/" + session + "/messages", a, Map.of("content", "add a price table"), 200);
        String request = llm.seen().get(0).get(llm.seen().get(0).size() - 1).text();
        assertThat(request).contains("PROJECT MEMORY").contains("[#" + facts.get(0).path("id").asLong() + "] Prices are shown in Indian rupees")
                .contains("Brand colour is #2f6f4f");
    }

    @Test
    void theAgentCanCorrectAFactAndDuplicatesAreNotSavedTwice() {
        Account a = signup("memory-fix");
        long id = site(a);
        long old = post(mem(id), a, Map.of("content", "Brand colour is navy"), 201).path("id").asLong();
        llm.then(calls(new ToolCall("forget", Map.of("id", old)), remember("Brand colour is #2f6f4f"),
                        remember("brand colour is #2f6f4f ")))
           .then(calls(finish("Updated.")));

        JsonNode r = post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "our brand colour changed to #2f6f4f"), 200);

        var results = llm.seen().get(1).get(llm.seen().get(1).size() - 1).toolResults();
        assertThat(results.get(0).output()).isEqualTo("Forgot memory #" + old + ".");
        assertThat(results.get(2).output()).startsWith("Already in project memory as #");
        assertThat(get(mem(id), a, 200)).hasSize(1);
        assertThat(r.path("agent").path("remembered")).hasSize(1);
    }

    @Test
    void credentialsAndOverlongFactsAreRefusedForAgentsAndPeopleAlike() {
        Account a = signup("memory-guard");
        long id = site(a);
        post(mem(id), a, Map.of("content", "Stripe key is " + com.forgeflow.support.FakeSecrets.STRIPE_SECRET_KEY), 400);
        post(mem(id), a, Map.of("content", "x".repeat(301)), 400);
        llm.then(calls(remember("The API key is " + com.forgeflow.support.FakeSecrets.GOOGLE_API_KEY))).then(calls(finish("Ok.")));
        post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "remember my key"), 200);
        String refusal = llm.seen().get(1).get(llm.seen().get(1).size() - 1).toolResults().get(0).output();
        assertThat(refusal).startsWith("ERROR: That looks like a real Google API key");
        assertThat(get(mem(id), a, 200)).isEmpty();
    }

    @Test
    void viewersReadMemoryButOnlyEditorsChangeIt() {
        Account owner = signup("memory-owner");
        Account viewer = signup("memory-viewer");
        Account stranger = signup("memory-stranger");
        long id = site(owner);
        post("/api/v1/projects/" + id + "/members", owner, Map.of("email", viewer.email(), "role", "VIEWER"), 201);
        long fact = post(mem(id), owner, Map.of("content", "Keep it to a single page"), 201).path("id").asLong();

        assertThat(get(mem(id), viewer, 200)).hasSize(1);
        post(mem(id), viewer, Map.of("content", "Make it pink"), 403);
        call(HttpMethod.DELETE, mem(id) + "/" + fact, viewer, null, 403);
        get(mem(id), stranger, 404);
        call(HttpMethod.DELETE, mem(id) + "/" + fact, owner, null, 204);
        call(HttpMethod.DELETE, mem(id) + "/" + fact, owner, null, 404);
    }

    @Test
    void whenMemoryOutgrowsTheBudgetFactsAboutTheRequestComeFirst() {
        Account a = signup("memory-budget");
        long id = site(a);
        for (int i = 0; i < 40; i++) {
            post(mem(id), a, Map.of("content", "Filler fact number " + i + " about something unrelated to anything here at all, padded out"), 201);
        }
        post(mem(id), a, Map.of("content", "The newsletter signup goes in the footer"), 201);
        llm.then(calls(finish("Ok.")));
        post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "restyle the newsletter form"), 200);

        String request = llm.seen().get(0).get(0).text();
        assertThat(request).contains("The newsletter signup goes in the footer").contains("more not shown)");
        assertThat(request.substring(request.indexOf("PROJECT MEMORY")).length()).isLessThan(2_700);
    }
}
