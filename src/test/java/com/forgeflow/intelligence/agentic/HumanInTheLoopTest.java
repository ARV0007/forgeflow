package com.forgeflow.intelligence.agentic;

import com.forgeflow.shared.llm.ToolCall;
import com.forgeflow.support.ApiTestSupport;
import com.forgeflow.workspace.ProjectFileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

/** Human in the loop: the agent can stop to ask, and can only ask to delete - a person says yes or no. */
class HumanInTheLoopTest extends ApiTestSupport {

    @Autowired
    ProjectFileService files;

    private long site(Account a) {
        long id = createProject(a, "hitl");
        files.write(id, "index.html", INDEX, a.id());
        files.write(id, "styles.css", CSS, a.id());
        files.write(id, "app.js", JS, a.id());
        files.write(id, "old-banner.js", "console.log('old banner');", a.id());
        return id;
    }

    private String sessions(long id) {
        return "/api/v1/projects/" + id + "/chat/sessions";
    }

    @Test
    void theAgentAsksTheChatShowsTheQuestionAndTheAnswerContinuesTheWork() {
        Account a = signup("ask");
        long id = site(a);
        long session = post(sessions(id), a, Map.of(), 201).path("id").asLong();
        llm.then(calls(new ToolCall("ask_user", Map.of("question", "Which page should get the contact form?",
                "options", List.of("Home", "About", "A new Contact page")))));

        JsonNode turn = post(sessions(id) + "/" + session + "/messages", a, Map.of("content", "add a contact form"), 200);

        JsonNode reply = turn.path("assistantMessage");
        assertThat(reply.path("status").asText()).isEqualTo("NEEDS_INPUT");
        assertThat(reply.path("content").asText()).isEqualTo("Which page should get the contact form?");
        assertThat(reply.path("agent").path("options").toString()).contains("A new Contact page");
        assertThat(turn.path("run").path("stopReason").asText()).isEqualTo("ASKED_USER");
        assertThat(turn.path("run").path("buildPassed").isNull()).isTrue();      // nothing built yet

        // The answer is just the next message - and the model sees its own question in the conversation.
        llm.reset();
        llm.then(calls(write("contact.html", INDEX.replace("Hello", "Contact")))).then(calls(finish("Added contact.html.")));
        JsonNode next = post(sessions(id) + "/" + session + "/messages", a, Map.of("content", "A new Contact page"), 200);
        assertThat(next.path("assistantMessage").path("status").asText()).isEqualTo("SUCCEEDED");
        String memory = llm.seen().get(0).stream().map(m -> String.valueOf(m.text())).reduce("", String::concat);
        assertThat(memory).contains("Which page should get the contact form?").contains("A new Contact page");
    }

    @Test
    void deletingNeedsAYesAndRejectingKeepsTheFile() {
        Account a = signup("approve");
        long id = site(a);
        llm.then(calls(new ToolCall("delete_file", Map.of("path", "old-banner.js", "reason", "Nothing loads it any more")),
                        new ToolCall("delete_file", Map.of("path", "styles.css", "reason", "Inline the styles"))))
           .then(calls(finish("Asked to remove two unused files.")));

        JsonNode r = post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "clean up unused files"), 200);

        assertThat(r.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(files.read(id, "old-banner.js")).isPresent();               // nothing deleted by the agent
        assertThat(r.path("agent").path("approvals")).hasSize(2);
        String said = llm.seen().get(1).get(llm.seen().get(1).size() - 1).toolResults().get(0).output();
        assertThat(said).contains("needs the user's approval").contains("the file stays");

        JsonNode pending = get("/api/v1/projects/" + id + "/approvals?pending=true", a, 200);
        assertThat(pending).hasSize(2);
        long banner = r.path("agent").path("approvals").get(0).asLong();
        long styles = r.path("agent").path("approvals").get(1).asLong();

        post("/api/v1/projects/" + id + "/approvals/" + banner + "/approve", a, null, 200);
        post("/api/v1/projects/" + id + "/approvals/" + styles + "/reject", a, null, 200);
        post("/api/v1/projects/" + id + "/approvals/" + banner + "/reject", a, null, 400);    // decided once

        assertThat(files.read(id, "old-banner.js")).isEmpty();
        assertThat(files.read(id, "styles.css")).isPresent();
        // The approved delete is itself undoable: the file's last content is a version.
        JsonNode history = get("/api/v1/projects/" + id + "/checkpoints", a, 200);
        assertThat(history.toString()).contains("Deleted old-banner.js (approved)");
    }

    @Test
    void viewersCanSeeRequestsButNotDecideThem() {
        Account owner = signup("approve-owner");
        Account viewer = signup("approve-viewer");
        long id = site(owner);
        post("/api/v1/projects/" + id + "/members", owner, Map.of("email", viewer.email(), "role", "VIEWER"), 201);
        llm.then(calls(new ToolCall("delete_file", Map.of("path", "old-banner.js", "reason", "unused")))).then(calls(finish("Asked.")));
        long req = post("/api/v1/projects/" + id + "/generate", owner, Map.of("prompt", "tidy"), 200)
                .path("agent").path("approvals").get(0).asLong();

        assertThat(get("/api/v1/projects/" + id + "/approvals", viewer, 200)).hasSize(1);
        post("/api/v1/projects/" + id + "/approvals/" + req + "/approve", viewer, null, 403);
        assertThat(files.read(id, "old-banner.js")).isPresent();
    }

    @Test
    void emptyingAFileIsRefusedAsADeleteInDisguise() {
        Account a = signup("approve-empty");
        long id = site(a);
        llm.then(calls(write("old-banner.js", "  "))).then(calls(finish("Done.")));

        post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "remove the banner script"), 200);

        String said = llm.seen().get(1).get(llm.seen().get(1).size() - 1).toolResults().get(0).output();
        assertThat(said).contains("Refused: that would empty old-banner.js").contains("delete_file");
        assertThat(files.read(id, "old-banner.js").orElseThrow()).contains("old banner");
    }
}
