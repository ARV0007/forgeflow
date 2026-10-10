package com.forgeflow.execution;

import com.forgeflow.support.FakeKubernetes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A namespace and a pod per preview, against a fake Kubernetes API server. */
class KubernetesSandboxProviderTest {

    private final FakeKubernetes k8s = new FakeKubernetes();
    private final KubernetesSandboxProvider provider = new KubernetesSandboxProvider(
            new KubernetesApi(k8s.url(), "test-token", HttpClient.newHttpClient()),
            "preview.test", "https", "nginxinc/nginx-unprivileged:1.27-alpine", "node:20-alpine", "forgeflow",
            Duration.ofMinutes(30), Duration.ofSeconds(5), Duration.ofMillis(10));

    static final Map<String, String> SITE = Map.of("index.html", "<h1>Hi</h1>", "css/site.css", "h1{}");
    static final Map<String, String> REACT = Map.of("package.json", "{}", "index.html", "<div id=root>",
            "src/App.jsx", "export default () => <h1/>");

    @AfterEach
    void stop() {
        k8s.close();
    }

    @Test
    void aStaticPreviewGetsItsOwnLockedDownNamespace() {
        PreviewHandle h = provider.startPreview(456L, SITE);

        String ns = "ffp-" + h.containerName();
        assertThat(h.url()).isEqualTo("https://" + h.containerName() + ".preview.test/");
        assertThat(h.containerName()).matches("[0-9a-f]{32}");                 // the unguessable part of the address
        assertThat(k8s.namespaces.get(ns)).containsEntry("forgeflow.dev/project-id", "456");
        assertThat(k8s.calls).allMatch(c -> "Bearer test-token".equals(c.auth()));

        // Created in this order: the fence before anything that runs inside it.
        assertThat(k8s.calls(("POST"), "/").stream().map(c -> c.path().replaceAll(".*/", ""))).containsExactly(
                "namespaces", "resourcequotas", "networkpolicies", "configmaps", "pods", "services");

        JsonNode policy = k8s.object(ns, "networkpolicies", "preview").path("spec");
        assertThat(policy.path("egress")).isEmpty();                            // a static site calls nobody
        assertThat(policy.path("ingress").get(0).path("from").get(0).path("namespaceSelector").path("matchLabels")
                .path("kubernetes.io/metadata.name").asText()).isEqualTo("forgeflow");

        JsonNode files = k8s.object(ns, "configmaps", "files").path("data");
        assertThat(files.path("css_2fsite.css").asText()).isEqualTo("h1{}");    // "/" can't be in a key

        JsonNode spec = k8s.object(ns, "pods", "preview").path("spec");
        assertThat(spec.path("automountServiceAccountToken").asBoolean(true)).isFalse();
        assertThat(spec.path("activeDeadlineSeconds").asLong()).isEqualTo(1800);
        assertThat(spec.path("securityContext").path("runAsNonRoot").asBoolean()).isTrue();
        JsonNode web = spec.path("containers").get(0);
        assertThat(web.path("image").asText()).isEqualTo("nginxinc/nginx-unprivileged:1.27-alpine");
        assertThat(web.path("securityContext").path("readOnlyRootFilesystem").asBoolean()).isTrue();
        assertThat(web.path("securityContext").path("capabilities").path("drop").get(0).asText()).isEqualTo("ALL");
        JsonNode items = spec.path("volumes").get(0).path("configMap").path("items");
        assertThat(items).anySatisfy(i -> {
            assertThat(i.path("key").asText()).isEqualTo("css_2fsite.css");
            assertThat(i.path("path").asText()).isEqualTo("css/site.css");      // ...and back to its real path
        });
        assertThat(k8s.object(ns, "services", "preview").path("spec").path("ports").get(0).path("targetPort").asInt())
                .isEqualTo(8080);
    }

    @Test
    void aReactPreviewRunsViteOnPort3000AndMayReachNpm() {
        PreviewHandle h = provider.startPreview(456L, REACT);
        String ns = "ffp-" + h.containerName();

        JsonNode dev = k8s.object(ns, "pods", "preview").path("spec").path("containers").get(0);
        assertThat(dev.path("image").asText()).isEqualTo("node:20-alpine");
        assertThat(dev.path("command").get(2).asText()).contains("npm install").contains("vite --host 0.0.0.0 --port 3000");
        assertThat(dev.path("ports").get(0).path("containerPort").asInt()).isEqualTo(3000);
        assertThat(k8s.object(ns, "services", "preview").path("spec").path("ports").get(0).path("targetPort").asInt())
                .isEqualTo(3000);
        assertThat(k8s.object(ns, "networkpolicies", "preview").path("spec").path("egress").toString())
                .contains("443").contains("53");
    }

    @Test
    void startingAgainReplacesTheOldPreviewAndStopFindsItByLabel() {
        PreviewHandle first = provider.startPreview(7L, SITE);
        PreviewHandle second = provider.startPreview(7L, SITE);
        provider.startPreview(8L, SITE);                                         // someone else's

        assertThat(k8s.namespaces).doesNotContainKey("ffp-" + first.containerName())
                .containsKey("ffp-" + second.containerName());
        provider.stopPreview(7L);
        assertThat(k8s.namespaces.values()).extracting(l -> l.get("forgeflow.dev/project-id")).containsExactly("8");
    }

    @Test
    void aPodThatCannotPullItsImageFailsFastAndLeavesNothingBehind() {
        k8s.podWaitingReason = "ImagePullBackOff";
        assertThatThrownBy(() -> provider.startPreview(9L, SITE))
                .isInstanceOf(SandboxException.class).hasMessageContaining("ImagePullBackOff");
        assertThat(k8s.namespaces).isEmpty();
    }

    @Test
    void codeGeneratedRedeploysThePodInPlaceKeepingTheAddress() {
        PreviewHandle h = provider.startPreview(11L, SITE);
        String ns = "ffp-" + h.containerName();
        k8s.conflictsBeforePodCreate.set(2);                                    // the old pod is still terminating

        provider.refreshPreview(11L, Map.of("index.html", "<h1>New</h1>"));

        assertThat(k8s.object(ns, "configmaps", "files").path("data").path("index.html").asText()).isEqualTo("<h1>New</h1>");
        assertThat(k8s.calls("DELETE", "/pods/preview")).hasSize(1);
        assertThat(k8s.calls("POST", ns + "/pods")).hasSize(4);                  // first start, 2 refused, the redeploy
        assertThat(k8s.namespaces).containsKey(ns);                             // same namespace, same URL
    }

    @Test
    void tooBigForAConfigMapIsRefusedBeforeTouchingTheCluster() {
        assertThatThrownBy(() -> provider.startPreview(12L, Map.of("index.html", "x".repeat(950_000))))
                .isInstanceOf(SandboxException.class).hasMessageContaining("ConfigMap");
        assertThat(k8s.calls).isEmpty();
    }

    @Test
    void keysEscapeEverythingAConfigMapCannotHold() {
        assertThat(KubernetesSandboxProvider.key("src/App.jsx")).isEqualTo("src_2fApp.jsx");
        assertThat(KubernetesSandboxProvider.key("a_b c.js")).isEqualTo("a_5fb_20c.js");
        assertThat(List.of("a/b", "a_2fb").stream().map(KubernetesSandboxProvider::key).distinct()).hasSize(2);
    }
}
