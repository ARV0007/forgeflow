package com.forgeflow.streaming;

import com.forgeflow.support.ScriptedLlm;
import com.forgeflow.support.TestLlmConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static com.forgeflow.support.ScriptedLlm.CSS;
import static com.forgeflow.support.ScriptedLlm.INDEX;
import static com.forgeflow.support.ScriptedLlm.JS;
import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static com.forgeflow.support.ScriptedLlm.write;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE against a REAL Tomcat, over a real socket.
 *
 * MockMvc never performs the async re-dispatch that ends a Servlet async
 * request, so it cannot see what happens there. In a real server, finishing
 * an SseEmitter re-dispatches the request through the whole filter chain -
 * including Spring Security, which (stateless, JWT filter runs once) found
 * nobody authenticated and threw AccessDenied onto an already-committed
 * response. The run succeeded; the browser saw the stream break. This test
 * exists because the browser walk-through caught that and MockMvc couldn't.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestLlmConfig.class)
class RealServerStreamTest {

    @LocalServerPort
    int port;

    @Autowired
    ScriptedLlm llm;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    private HttpResponse<String> send(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aChatStreamEndsCleanlyWithItsFinalMessage() throws Exception {
        llm.reset();
        String email = "stream-" + UUID.randomUUID().toString().substring(0, 8) + "@test.dev";
        JsonNode auth = json.readTree(send("POST", "/api/v1/auth/signup", null,
                java.util.Map.of("email", email, "password", "correct-horse-1")).body());
        String token = auth.path("token").asText();
        long project = json.readTree(send("POST", "/api/v1/projects", token,
                java.util.Map.of("name", "stream")).body()).path("id").asLong();
        long session = json.readTree(send("POST", "/api/v1/projects/" + project + "/chat/sessions", token,
                java.util.Map.of()).body()).path("id").asLong();

        llm.then(calls(write("index.html", INDEX), write("styles.css", CSS), write("app.js", JS)))
           .then(calls(finish("Built.")));

        HttpResponse<String> stream = send("POST",
                "/api/v1/projects/" + project + "/chat/sessions/" + session + "/messages/stream",
                token, java.util.Map.of("content", "build a page"));

        assertThat(stream.statusCode()).isEqualTo(200);
        assertThat(stream.body()).contains("event:done").contains("event:message");
        // The last event is the saved reply, intact - not cut off by an error page.
        String last = stream.body().substring(stream.body().lastIndexOf("event:message"));
        JsonNode turn = json.readTree(last.substring(last.indexOf("data:") + 5).trim());
        assertThat(turn.path("assistantMessage").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(stream.body()).doesNotContain("Access Denied").doesNotContain("\"status\":403");
    }
}
