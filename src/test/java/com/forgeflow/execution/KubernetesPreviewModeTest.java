package com.forgeflow.execution;

import com.forgeflow.support.ApiTestSupport;
import com.forgeflow.support.FakeKubernetes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static com.forgeflow.support.ScriptedLlm.INDEX;
import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static com.forgeflow.support.ScriptedLlm.write;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The diagram's execution path, end to end with the whole app:
 * start a preview -> a namespace and a pod; the agent changes a file ->
 * code.generated -> the execution consumer -> the pod is redeployed with the
 * new files, at the same address.
 */
class KubernetesPreviewModeTest extends ApiTestSupport {

    static final FakeKubernetes k8s = new FakeKubernetes();

    @DynamicPropertySource
    static void kubernetes(DynamicPropertyRegistry r) {
        r.add("forgeflow.sandbox.provider", () -> "kubernetes");
        r.add("forgeflow.sandbox.kubernetes.api-url", k8s::url);
        r.add("forgeflow.sandbox.kubernetes.token", () -> "t");
        r.add("forgeflow.sandbox.kubernetes.preview-domain", () -> "preview.test");
        r.add("forgeflow.sandbox.kubernetes.ready-timeout-seconds", () -> "10");
    }

    @AfterAll
    static void stop() {
        k8s.close();
    }

    @Test
    void aPreviewIsAPodAndCodeGeneratedRedeploysIt() {
        Account a = signup("k8s");
        long id = createProject(a, "in-a-pod");
        llm.then(calls(write("index.html", INDEX.replace("<link rel=\"stylesheet\" href=\"styles.css\">", "")
                        .replace("<script src=\"app.js\"></script>", ""))))
           .then(calls(finish("Built.")));
        assertThat(post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "Hello page"), 200)
                .path("status").asText()).isEqualTo("SUCCEEDED");

        JsonNode preview = post("/api/v1/projects/" + id + "/preview", a, null, 200);
        String url = preview.path("url").asText();
        assertThat(url).matches("https://[0-9a-f]{32}\\.preview\\.test/");
        String ns = "ffp-" + url.substring("https://".length(), url.indexOf('.'));
        assertThat(k8s.namespaces.get(ns)).containsEntry("forgeflow.dev/project-id", String.valueOf(id));

        llm.then(calls(write("index.html", "<!doctype html><html><body><h1>Changed</h1></body></html>")))
           .then(calls(finish("Changed it.")));
        post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "Change the heading"), 200);

        assertThat(k8s.object(ns, "configmaps", "files").path("data").path("index.html").asText()).contains("Changed");
        assertThat(get("/api/v1/projects/" + id + "/preview", a, 200).path("url").asText()).isEqualTo(url);
        assertThat(get("/api/v1/projects/" + id + "/preview/logs", a, 200).toString())
                .contains("preview redeployed with the new files");

        call(org.springframework.http.HttpMethod.DELETE, "/api/v1/projects/" + id + "/preview", a, null, 204);
        assertThat(k8s.namespaces).doesNotContainKey(ns);
    }
}
