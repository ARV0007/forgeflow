package com.forgeflow.chat;

import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static com.forgeflow.support.ScriptedLlm.CSS;
import static com.forgeflow.support.ScriptedLlm.INDEX;
import static com.forgeflow.support.ScriptedLlm.JS;
import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static com.forgeflow.support.ScriptedLlm.write;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Long chat memory: an exchange that scrolled out of the last-10 window comes
 * back when a new request refers to it; nothing is recalled or embedded for a
 * chat that still fits the window.
 */
class ChatRecallTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    private String sessions(long projectId) {
        return "/api/v1/projects/" + projectId + "/chat/sessions";
    }

    private void say(long sessionId, Long author, String role, String content) {
        jdbc.update("INSERT INTO chat_messages (session_id, role, content, author_id, status) VALUES (?, ?, ?, ?, ?)",
                sessionId, role, content, "user".equals(role) ? author : null, "user".equals(role) ? null : "SUCCEEDED");
    }

    private void buildsCleanly() {
        llm.then(calls(write("index.html", INDEX), write("styles.css", CSS), write("app.js", JS)))
           .then(calls(finish("Done.")));
    }

    @Test
    void anOldExchangeComesBackWhenTheRequestRefersToIt() {
        Account a = signup("recall");
        long id = createProject(a, "long-chat");
        long session = post(sessions(id), a, Map.of(), 201).path("id").asLong();

        say(session, a.id(), "user", "Make the footer dark green with a newsletter signup form");
        say(session, a.id(), "assistant", "Added a dark green footer with a newsletter signup form. [Files written: index.html]");
        for (int i = 0; i < 14; i++) {                       // 28 unrelated messages push it far out of the window
            say(session, a.id(), "user", "Change the heading text to version " + i);
            say(session, a.id(), "assistant", "Updated the heading to version " + i + ".");
        }

        buildsCleanly();
        int before = llm.seen().size();
        JsonNode reply = post(sessions(id) + "/" + session + "/messages", a,
                Map.of("content", "Give the header the same newsletter footer style"), 200);
        assertThat(reply.path("assistantMessage").path("status").asText()).isEqualTo("SUCCEEDED");

        String request = llm.seen().get(before).get(llm.seen().get(before).size() - 1).text();
        assertThat(request).contains("EARLIER IN THIS CHAT")
                .contains("[The user asked] Make the footer dark green with a newsletter signup form")
                .contains("[You replied] Added a dark green footer");
        // Only messages older than the window were embedded - and now they keep their vectors.
        Integer embedded = jdbc.queryForObject(
                "SELECT count(*) FROM chat_messages WHERE session_id = ? AND embedding IS NOT NULL", Integer.class, session);
        assertThat(embedded).isEqualTo(30 - 10);
    }

    @Test
    void aShortChatRecallsNothingAndEmbedsNothing() {
        Account a = signup("recall-short");
        long id = createProject(a, "short-chat");
        long session = post(sessions(id), a, Map.of(), 201).path("id").asLong();
        say(session, a.id(), "user", "Make the footer dark green");
        say(session, a.id(), "assistant", "Done - the footer is dark green.");

        buildsCleanly();
        int before = llm.seen().size();
        post(sessions(id) + "/" + session + "/messages", a, Map.of("content", "Now match the footer colour in the header"), 200);

        String request = llm.seen().get(before).get(llm.seen().get(before).size() - 1).text();
        assertThat(request).doesNotContain("EARLIER IN THIS CHAT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE session_id = ? AND embedding IS NOT NULL",
                Integer.class, session)).isZero();
    }
}
