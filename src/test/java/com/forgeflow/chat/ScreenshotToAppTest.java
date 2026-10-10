package com.forgeflow.chat;

import com.forgeflow.shared.llm.LlmMessage;
import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.forgeflow.support.ScriptedLlm.CSS;
import static com.forgeflow.support.ScriptedLlm.INDEX;
import static com.forgeflow.support.ScriptedLlm.JS;
import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static com.forgeflow.support.ScriptedLlm.write;
import static org.assertj.core.api.Assertions.assertThat;

/** Screenshot to app: images ride with the message, reach the model, survive reload and retry. */
class ScreenshotToAppTest extends ApiTestSupport {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    static final String PNG64 = Base64.getEncoder().encodeToString(PNG);

    private String sessions(long id) {
        return "/api/v1/projects/" + id + "/chat/sessions";
    }

    private void scriptBuild() {
        llm.then(calls(write("index.html", INDEX), write("styles.css", CSS), write("app.js", JS)))
           .then(calls(finish("Built it from the screenshot.")));
    }

    private JsonNode say(Account a, long id, long sid, Object body, int status) {
        return post(sessions(id) + "/" + sid + "/messages", a, body, status);
    }

    @Test
    void anAttachedScreenshotReachesTheModelAsAnImageWithInstructions() throws Exception {
        Account a = signup("visual");
        long id = createProject(a, "from-a-picture");
        long sid = post(sessions(id), a, Map.of(), 201).path("id").asLong();

        scriptBuild();
        JsonNode turn = say(a, id, sid, Map.of("content", "Make this landing page",
                "images", List.of(Map.of("mimeType", "image/png", "data", PNG64))), 200);
        assertThat(turn.path("assistantMessage").path("status").asText()).isEqualTo("SUCCEEDED");

        LlmMessage asked = llm.seen().get(0).get(0);
        assertThat(asked.images()).hasSize(1);
        assertThat(asked.images().get(0).base64()).isEqualTo(PNG64);
        assertThat(asked.text()).startsWith("ATTACHED: 1 image").endsWith("Make this landing page");

        JsonNode sent = turn.path("userMessage").path("images");
        assertThat(sent).hasSize(1);
        long messageId = turn.path("userMessage").path("id").asLong();
        long imageId = sent.get(0).path("id").asLong();

        // After a reload: the history lists it and the bytes come back as the image they were.
        JsonNode history = get(sessions(id) + "/" + sid + "/messages", a, 200);
        assertThat(history.get(0).path("images").get(0).path("mimeType").asText()).isEqualTo("image/png");
        MvcResult img = mvc.perform(MockMvcRequestBuilders.get(sessions(id) + "/" + sid + "/messages/" + messageId
                + "/attachments/" + imageId).header("Authorization", "Bearer " + a.token())).andReturn();
        assertThat(img.getResponse().getContentType()).isEqualTo("image/png");
        assertThat(img.getResponse().getContentAsByteArray()).isEqualTo(PNG);

        // The next turn remembers there was a picture, without paying to send it again.
        llm.reset();
        scriptBuild();
        say(a, id, sid, Map.of("content", "Now make the button green"), 200);
        List<LlmMessage> memory = llm.seen().get(0);
        assertThat(memory.get(0).text()).contains("[1 image attached to this message]");
        assertThat(memory.get(0).images()).isEmpty();
        assertThat(memory.get(memory.size() - 1).images()).isEmpty();
    }

    @Test
    void retrySendsTheSameImagesAgain() {
        Account a = signup("retry-visual");
        long id = createProject(a, "retry");
        long sid = post(sessions(id), a, Map.of(), 201).path("id").asLong();

        // Nothing scripted: the model "fails" and the reply is saved as FAILED.
        JsonNode failed = say(a, id, sid, Map.of("content", "Copy this design",
                "images", List.of(Map.of("mimeType", "image/png", "data", PNG64))), 200);
        assertThat(failed.path("assistantMessage").path("status").asText()).isNotEqualTo("SUCCEEDED");

        llm.reset();
        scriptBuild();
        post(sessions(id) + "/" + sid + "/retry", a, null, 200);
        assertThat(llm.seen().get(0).get(llm.seen().get(0).size() - 1).images()).hasSize(1);
    }

    @Test
    void badImagesAreRefusedBeforeAnythingIsSavedAndStrangersCantFetchImages() {
        Account a = signup("careful");
        Account stranger = signup("stranger");
        long id = createProject(a, "guarded");
        long sid = post(sessions(id), a, Map.of(), 201).path("id").asLong();

        say(a, id, sid, Map.of("content", "x", "images", List.of(Map.of("mimeType", "image/gif", "data", PNG64))), 400);
        say(a, id, sid, Map.of("content", "x", "images", List.of(Map.of("mimeType", "image/jpeg", "data", PNG64))), 400);
        Map<String, String> one = Map.of("mimeType", "image/png", "data", PNG64);
        say(a, id, sid, Map.of("content", "x", "images", List.of(one, one, one, one)), 400);
        assertThat(get(sessions(id) + "/" + sid + "/messages", a, 200)).isEmpty();

        scriptBuild();
        JsonNode turn = say(a, id, sid, Map.of("content", "ok", "images", List.of(one)), 200);
        String url = sessions(id) + "/" + sid + "/messages/" + turn.path("userMessage").path("id").asLong()
                + "/attachments/" + turn.path("userMessage").path("images").get(0).path("id").asLong();
        get(url, stranger, 404);
    }
}
