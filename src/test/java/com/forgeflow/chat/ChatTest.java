package com.forgeflow.chat;

import com.forgeflow.shared.llm.LlmMessage;
import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
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

/**
 * Chat sessions: the spec's five AI-generation features, and above all that a
 * conversation REMEMBERS - the second message is answered with the first one
 * in view.
 */
class ChatTest extends ApiTestSupport {

    @Autowired
    SessionLocks locks;

    private String sessions(long projectId) {
        return "/api/v1/projects/" + projectId + "/chat/sessions";
    }

    private long newSession(Account a, long projectId) {
        return post(sessions(projectId), a, Map.of(), 201).path("id").asLong();
    }

    private void scriptSuccessfulReply(String summary) {
        llm.then(calls(write("index.html", INDEX), write("styles.css", CSS), write("app.js", JS)))
           .then(calls(finish(summary)));
    }

    private JsonNode say(Account a, long projectId, long sessionId, String text, int status) {
        return post(sessions(projectId) + "/" + sessionId + "/messages", a, Map.of("content", text), status);
    }

    @Test
    void sessionsAreListedAndReadableByViewersButCreatedOnlyByWriters() {
        Account owner = signup("owner");
        Account viewer = signup("viewer");
        long id = createProject(owner, "chatty");
        post("/api/v1/projects/" + id + "/members", owner, Map.of("email", viewer.email(), "role", "VIEWER"), 201);

        long sid = newSession(owner, id);

        assertThat(get(sessions(id), viewer, 200)).hasSize(1);
        get(sessions(id) + "/" + sid + "/messages", viewer, 200);
        post(sessions(id), viewer, Map.of(), 403);
        say(viewer, id, sid, "let me in", 403);
    }

    @Test
    void theSecondMessageIsAnsweredWithTheFirstInView() {
        Account a = signup("chat");
        long id = createProject(a, "memory");
        long sid = newSession(a, id);

        scriptSuccessfulReply("Built a landing page with a blue header.");
        JsonNode first = say(a, id, sid, "build me a landing page", 200);
        assertThat(first.path("assistantMessage").path("status").asText()).isEqualTo("SUCCEEDED");

        llm.reset();
        scriptSuccessfulReply("Made the header red.");
        say(a, id, sid, "make the header red", 200);

        // The model's very first view of the second request: the whole earlier
        // exchange, then the new ask. This is the conversation memory.
        List<LlmMessage> seen = llm.seen().get(0);
        assertThat(seen).hasSize(3);
        assertThat(seen.get(0).role()).isEqualTo(LlmMessage.Role.USER);
        assertThat(seen.get(0).text()).isEqualTo("build me a landing page");
        assertThat(seen.get(1).role()).isEqualTo(LlmMessage.Role.MODEL);
        assertThat(seen.get(1).text()).startsWith("Built a landing page with a blue header.")
                .contains("[Files written: index.html, styles.css, app.js]");
        assertThat(seen.get(2).text()).isEqualTo("make the header red");
    }

    @Test
    void fullHistoryAlternatesAndRecordsWhatTheAgentDid() {
        Account a = signup("chat");
        long id = createProject(a, "history");
        long sid = newSession(a, id);
        scriptSuccessfulReply("Built it.");
        say(a, id, sid, "build it", 200);

        JsonNode history = get(sessions(id) + "/" + sid + "/messages", a, 200);

        assertThat(history).hasSize(2);
        assertThat(history.get(0).path("role").asText()).isEqualTo("user");
        assertThat(history.get(0).path("authorId").asLong()).isEqualTo(a.id());
        JsonNode reply = history.get(1);
        assertThat(reply.path("role").asText()).isEqualTo("assistant");
        assertThat(reply.path("toolCalls")).hasSize(3);
        assertThat(reply.path("toolCalls").get(0).path("path").asText()).isEqualTo("index.html");
        assertThat(reply.path("tokensUsed").asInt()).isPositive();
        assertThat(reply.path("runId").asLong()).isPositive();
    }

    @Test
    void anUntitledSessionIsNamedAfterItsFirstMessage() {
        Account a = signup("chat");
        long id = createProject(a, "titles");
        long sid = newSession(a, id);
        scriptSuccessfulReply("ok");
        say(a, id, sid, "A portfolio site for a photographer", 200);

        assertThat(get(sessions(id), a, 200).get(0).path("title").asText())
                .isEqualTo("A portfolio site for a photographer");
    }

    /** Spec: "Retry if failed" - and a failure is kept, not swallowed. */
    @Test
    void aFailedReplyStaysInTheHistoryAndCanBeRetried() {
        Account a = signup("chat");
        long id = createProject(a, "retry");
        long sid = newSession(a, id);

        // Nothing scripted: the provider "fails".
        JsonNode failed = say(a, id, sid, "build a todo app", 200);
        assertThat(failed.path("assistantMessage").path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("assistantMessage").path("content").asText()).startsWith("Something went wrong");

        scriptSuccessfulReply("Built the todo app.");
        JsonNode retried = post(sessions(id) + "/" + sid + "/retry", a, null, 200);

        assertThat(retried.path("userMessage").isNull()).as("a retry asks nothing new").isTrue();
        assertThat(retried.path("assistantMessage").path("status").asText()).isEqualTo("SUCCEEDED");
        // The retry saw the question fresh - not its own failure notice.
        List<LlmMessage> seen = llm.seen().get(0);
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).text()).isEqualTo("build a todo app");

        // Nothing failed any more, so there is nothing to retry.
        post(sessions(id) + "/" + sid + "/retry", a, null, 409);
    }

    @Test
    void afterARetryTheNextMessageRemembersOnlyTheSuccessfulReply() {
        Account a = signup("chat");
        long id = createProject(a, "retry-memory");
        long sid = newSession(a, id);
        say(a, id, sid, "build a todo app", 200);              // fails
        scriptSuccessfulReply("Built the todo app.");
        post(sessions(id) + "/" + sid + "/retry", a, null, 200);

        llm.reset();
        scriptSuccessfulReply("Added dark mode.");
        say(a, id, sid, "add dark mode", 200);

        // user, assistant(FAILED), assistant(SUCCEEDED), user - collapsed so it
        // still alternates, keeping the reply that worked.
        List<LlmMessage> seen = llm.seen().get(0);
        assertThat(seen).extracting(LlmMessage::role).containsExactly(
                LlmMessage.Role.USER, LlmMessage.Role.MODEL, LlmMessage.Role.USER);
        assertThat(seen.get(1).text()).startsWith("Built the todo app.");
    }

    @Test
    void retryIsRefusedWhenNothingFailed() {
        Account a = signup("chat");
        long id = createProject(a, "no-retry");
        long sid = newSession(a, id);
        post(sessions(id) + "/" + sid + "/retry", a, null, 409);    // empty session

        scriptSuccessfulReply("ok");
        say(a, id, sid, "build it", 200);
        post(sessions(id) + "/" + sid + "/retry", a, null, 409);    // last reply succeeded
    }

    @Test
    void aSecondMessageWhileOneIsInFlightIsRefused() {
        Account a = signup("chat");
        long id = createProject(a, "busy");
        long sid = newSession(a, id);

        assertThat(locks.tryAcquire(sid)).isTrue();          // a reply is "in flight"
        try {
            say(a, id, sid, "and another thing", 409);
        } finally {
            locks.release(sid);
        }
        assertThat(get(sessions(id) + "/" + sid + "/messages", a, 200))
                .as("the refused message was not saved").isEmpty();
    }

    @Test
    void aSessionIdFromAnotherProjectIsNotFound() {
        Account a = signup("chat");
        long mine = createProject(a, "one");
        long other = createProject(a, "two");
        long sid = newSession(a, other);

        get(sessions(mine) + "/" + sid + "/messages", a, 404);
        say(a, mine, sid, "cross the streams", 404);
    }

    @Test
    void renameAndDelete() {
        Account a = signup("chat");
        long id = createProject(a, "tidy");
        long sid = newSession(a, id);

        call(HttpMethod.PATCH, sessions(id) + "/" + sid, a, Map.of("title", "Landing page"), 200);
        assertThat(get(sessions(id), a, 200).get(0).path("title").asText()).isEqualTo("Landing page");

        call(HttpMethod.DELETE, sessions(id) + "/" + sid, a, null, 204);
        assertThat(get(sessions(id), a, 200)).isEmpty();
        get(sessions(id) + "/" + sid + "/messages", a, 404);
    }

    /** Spec: "Chat Stream" - progress events, then the saved reply as the final event. */
    @Test
    void theStreamCarriesProgressAndEndsWithTheSavedReply() throws Exception {
        Account a = signup("chat");
        long id = createProject(a, "stream");
        long sid = newSession(a, id);
        scriptSuccessfulReply("Streamed it.");

        MvcResult result = mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(sessions(id) + "/" + sid + "/messages/stream")
                        .header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("content", "build it"))))
                .andReturn();

        String body = "";
        for (int i = 0; i < 100 && !body.contains("event:message"); i++) {
            Thread.sleep(50);
            body = result.getResponse().getContentAsString();
        }
        assertThat(body).contains("event:status", "event:file", "event:build", "event:done", "event:message");
        assertThat(body.indexOf("event:message")).isGreaterThan(body.indexOf("event:done"));
        assertThat(body).contains("Streamed it.");
    }
}
