package com.forgeflow.chat;

import com.forgeflow.shared.llm.LlmMessage;
import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.util.Base64;
import java.util.Map;

import static com.forgeflow.support.ScriptedLlm.CSS;
import static com.forgeflow.support.ScriptedLlm.INDEX;
import static com.forgeflow.support.ScriptedLlm.JS;
import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static com.forgeflow.support.ScriptedLlm.text;
import static com.forgeflow.support.ScriptedLlm.write;
import static org.assertj.core.api.Assertions.assertThat;

/** "AI checks its own app": a screenshot of the preview, judged by a vision model, kept with the reply. */
class VisualReviewTest extends ApiTestSupport {

    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0};
    static final Map<String, Object> SHOT = Map.of("screenshot",
            Map.of("mimeType", "image/jpeg", "data", Base64.getEncoder().encodeToString(JPEG)));

    @Autowired
    JdbcTemplate jdbc;

    private String sessions(long id) {
        return "/api/v1/projects/" + id + "/chat/sessions";
    }

    /** One chat turn that builds the site; returns the reply's id. */
    private long build(Account a, long id, long sid, String ask) {
        llm.then(calls(write("index.html", INDEX), write("styles.css", CSS), write("app.js", JS)))
           .then(calls(finish("Built it.")));
        return post(sessions(id) + "/" + sid + "/messages", a, Map.of("content", ask), 200)
                .path("assistantMessage").path("id").asLong();
    }

    private String reviewUrl(long id, long sid, long messageId) {
        return sessions(id) + "/" + sid + "/messages/" + messageId + "/visual-review";
    }

    @Test
    void theModelSeesTheScreenshotAndTheRequestsAndTheVerdictIsKeptWithTheReply() {
        Account a = signup("looker");
        long id = createProject(a, "pricing");
        long sid = post(sessions(id), a, Map.of(), 201).path("id").asLong();
        build(a, id, sid, "Build a pricing table with three plans");
        long reply = build(a, id, sid, "Make the middle plan stand out");

        llm.reset();
        // Sloppy on purpose: fenced, a sentence before it, and a label that
        // disagrees with the issues. The issues win.
        llm.then(text("""
                Here is my review:
                ```json
                {"score": 4, "verdict": "looks_right", "summary": "The middle plan does not stand out.",
                 "issues": [{"severity": "MAJOR", "text": "All three plan cards look identical"},
                            {"severity": "cosmetic", "text": "Prices are a little small"},
                            {"severity": "minor", "text": ""}]}
                ```"""));
        JsonNode review = post(reviewUrl(id, sid, reply), a, SHOT, 200);

        assertThat(review.path("messageId").asLong()).isEqualTo(reply);
        assertThat(review.path("verdict").asText()).isEqualTo("NEEDS_FIXES");
        assertThat(review.path("score").asInt()).isEqualTo(4);
        assertThat(review.path("issues")).hasSize(2);
        assertThat(review.path("issues").get(0).path("severity").asText()).isEqualTo("major");
        assertThat(review.path("issues").get(1).path("severity").asText()).isEqualTo("minor");
        assertThat(review.path("tokensUsed").asInt()).isPositive();

        // What the model was shown: the screenshot, both requests, the latest marked.
        LlmMessage asked = llm.seen().get(0).get(0);
        assertThat(asked.images()).hasSize(1);
        assertThat(asked.images().get(0).mimeType()).isEqualTo("image/jpeg");
        assertThat(asked.text()).contains("1. Build a pricing table with three plans")
                .contains("LATEST REQUEST: Make the middle plan stand out");

        // Metered against the caller's daily allowance, like every model call.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_logs WHERE user_id = ? AND ref = 'visual-review'",
                Long.class, a.id())).isEqualTo(1L);

        // Reloading the conversation shows the verdict on the reply it judged.
        JsonNode history = get(sessions(id) + "/" + sid + "/messages", a, 200);
        JsonNode last = history.get(history.size() - 1);
        assertThat(last.path("review").path("verdict").asText()).isEqualTo("NEEDS_FIXES");
        assertThat(history.get(history.size() - 2).path("review").isNull()).isTrue();     // the user's message

        // Reviewed again (after a fix, say): the newest verdict is the one shown.
        llm.then(text("{\"score\": 9, \"verdict\": \"looks_right\", \"summary\": \"Better.\", \"issues\": []}"));
        assertThat(post(reviewUrl(id, sid, reply), a, SHOT, 200).path("verdict").asText()).isEqualTo("LOOKS_RIGHT");
        JsonNode again = get(sessions(id) + "/" + sid + "/messages", a, 200);
        assertThat(again.get(again.size() - 1).path("review").path("score").asInt()).isEqualTo(9);
    }

    @Test
    void anUnreadableReviewIsA502AndNothingIsSaved() {
        Account a = signup("garbled");
        long id = createProject(a, "garbled");
        long sid = post(sessions(id), a, Map.of(), 201).path("id").asLong();
        long reply = build(a, id, sid, "Build a clock");

        llm.then(text("It looks great to me!"));
        post(reviewUrl(id, sid, reply), a, SHOT, 502);
        post(reviewUrl(id, sid, reply), a, SHOT, 503);                  // nothing scripted: the model is down
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visual_reviews WHERE message_id = ?", Long.class, reply))
                .isZero();
    }

    @Test
    void onlyEditorsOnlyFinishedRepliesOnlyRealImages() {
        Account owner = signup("rev-owner");
        Account viewer = signup("rev-viewer");
        Account stranger = signup("rev-stranger");
        long id = createProject(owner, "guarded");
        long sid = post(sessions(id), owner, Map.of(), 201).path("id").asLong();
        long reply = build(owner, id, sid, "Build a page");
        post("/api/v1/projects/" + id + "/members", owner, Map.of("email", viewer.email(), "role", "VIEWER"), 201);
        llm.reset();

        post(reviewUrl(id, sid, reply), viewer, SHOT, 403);               // costs tokens: editors only
        post(reviewUrl(id, sid, reply), stranger, SHOT, 404);
        long question = get(sessions(id) + "/" + sid + "/messages", owner, 200).get(0).path("id").asLong();
        post(reviewUrl(id, sid, question), owner, SHOT, 400);              // a user message isn't a result
        post(reviewUrl(id, sid, 999_999), owner, SHOT, 404);
        post(reviewUrl(id, sid, reply), owner, Map.of("screenshot",
                Map.of("mimeType", "image/png", "data", Base64.getEncoder().encodeToString(JPEG))), 400);   // lies about its type
        post(reviewUrl(id, sid, reply), owner, Map.of(), 400);
        assertThat(llm.seen()).isEmpty();                                  // no model call for any of these
    }
}
