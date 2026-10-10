package com.forgeflow.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spec: "code.generated -> execution-service -> Kubernetes pods
 * (project-456:3000, new namespace)". Each preview is its own namespace:
 *
 *   Namespace     ffp-{token}            labels: forgeflow.dev/project-id=456
 *   ResourceQuota quota                  2 pods, 1 CPU, 1 GiB at most
 *   NetworkPolicy preview                in: only from the gateway's namespace
 *                                        out: nothing (static) / DNS + 443 for npm (React)
 *   ConfigMap     files                  the project's files
 *   Pod           preview                static: nginx-unprivileged on :8080
 *                                        React:  node, npm install, vite on :3000
 *   Service       preview                :80 -> the pod
 *
 * Why a namespace per preview rather than pods in one namespace: isolation
 * by default (NetworkPolicy and quota apply to everything in it), and
 * cleanup is one call - deleting the namespace deletes all of it.
 *
 * The token in the namespace name is the unguessable part of the preview's
 * address: https://{token}.{preview-domain}/. A wildcard Ingress sends
 * *.{preview-domain} to the gateway, which forwards {token}.{domain} to
 * preview.ffp-{token}.svc - so no table maps tokens to pods anywhere, and any
 * API replica can stop or refresh any preview by its project label.
 *
 * The build check stays in the API (structure only, like in-process): it is
 * milliseconds of work and needs no network. The pod is where generated code
 * runs - so the pod is what gets locked down: non-root, read-only root
 * filesystem, every capability dropped, no service-account token, a hard
 * deadline equal to the preview's lifetime.
 *
 * Selected with forgeflow.sandbox.provider=kubernetes.
 */
@Component
@ConditionalOnProperty(name = "forgeflow.sandbox.provider", havingValue = "kubernetes")
public class KubernetesSandboxProvider implements SandboxProvider {

    private static final Logger log = LoggerFactory.getLogger(KubernetesSandboxProvider.class);

    static final String PROJECT_LABEL = "forgeflow.dev/project-id";
    static final String NS_PREFIX = "ffp-";
    /** A ConfigMap holds at most 1 MiB; leave room for keys and metadata. */
    static final int MAX_BYTES = 900_000;

    private final KubernetesApi k8s;
    private final InProcessSandboxProvider checks = new InProcessSandboxProvider();
    private final String previewDomain;
    private final String scheme;
    private final String staticImage;
    private final String nodeImage;
    private final String gatewayNamespace;
    private final Duration lifetime;
    private final Duration readyTimeout;
    private final Duration pollEvery;

    @Autowired
    public KubernetesSandboxProvider(
            @Value("${forgeflow.sandbox.kubernetes.api-url:}") String apiUrl,
            @Value("${forgeflow.sandbox.kubernetes.token:}") String token,
            @Value("${forgeflow.sandbox.kubernetes.preview-domain:preview.localhost}") String previewDomain,
            @Value("${forgeflow.sandbox.kubernetes.url-scheme:https}") String scheme,
            @Value("${forgeflow.sandbox.kubernetes.static-image:nginxinc/nginx-unprivileged:1.27-alpine}") String staticImage,
            @Value("${forgeflow.sandbox.kubernetes.node-image:node:20-alpine}") String nodeImage,
            @Value("${forgeflow.sandbox.kubernetes.gateway-namespace:forgeflow}") String gatewayNamespace,
            @Value("${forgeflow.sandbox.kubernetes.ready-timeout-seconds:180}") long readyTimeoutSeconds) {
        this(KubernetesApi.connect(apiUrl, token), previewDomain, scheme, staticImage, nodeImage, gatewayNamespace,
                ExecutionService.PREVIEW_TTL, Duration.ofSeconds(readyTimeoutSeconds), Duration.ofSeconds(1));
    }

    KubernetesSandboxProvider(KubernetesApi k8s, String previewDomain, String scheme, String staticImage,
                              String nodeImage, String gatewayNamespace, Duration lifetime, Duration readyTimeout,
                              Duration pollEvery) {
        this.k8s = k8s;
        this.previewDomain = previewDomain;
        this.scheme = scheme;
        this.staticImage = staticImage;
        this.nodeImage = nodeImage;
        this.gatewayNamespace = gatewayNamespace;
        this.lifetime = lifetime;
        this.readyTimeout = readyTimeout;
        this.pollEvery = pollEvery;
    }

    @Override
    public BuildResult build(Long projectId, Map<String, String> files) {
        return checks.build(projectId, files);
    }

    @Override
    public PreviewHandle startPreview(Long projectId, Map<String, String> files) {
        if (files.isEmpty()) {
            throw new SandboxException("The project has no files to preview.");
        }
        int bytes = files.entrySet().stream()
                .mapToInt(e -> e.getKey().length() + e.getValue().getBytes(StandardCharsets.UTF_8).length).sum();
        if (bytes > MAX_BYTES) {
            throw new SandboxException("The project is " + bytes / 1024 + " KB; a Kubernetes preview takes at most "
                    + MAX_BYTES / 1024 + " KB (its files travel in a ConfigMap)");
        }
        stopPreview(projectId);                    // one preview per project

        boolean react = files.containsKey("package.json");
        String token = UUID.randomUUID().toString().replace("-", "");
        String ns = NS_PREFIX + token;
        try {
            k8s.create("/api/v1/namespaces", namespace(ns, projectId));
            k8s.create("/api/v1/namespaces/" + ns + "/resourcequotas", quota());
            k8s.create("/apis/networking.k8s.io/v1/namespaces/" + ns + "/networkpolicies", networkPolicy(react));
            k8s.create("/api/v1/namespaces/" + ns + "/configmaps", configMap(files));
            k8s.create("/api/v1/namespaces/" + ns + "/pods", pod(files, react));
            k8s.create("/api/v1/namespaces/" + ns + "/services", service(react));
            waitUntilReady(ns);
        } catch (RuntimeException e) {
            k8s.delete("/api/v1/namespaces/" + ns);            // don't leave half a preview behind
            if (e instanceof SandboxException se) {
                throw se;
            }
            throw new SandboxException("Kubernetes refused the preview: " + e.getMessage());
        }
        String url = scheme + "://" + token + "." + previewDomain + "/";
        log.info("preview for project {} in namespace {} at {}", projectId, ns, url);
        return new PreviewHandle(projectId, token, url);
    }

    @Override
    public void stopPreview(Long projectId) {
        for (String ns : namespacesOf(projectId)) {
            k8s.delete("/api/v1/namespaces/" + ns);
        }
    }

    @Override
    public boolean previewsCopyFiles() {
        return true;
    }

    /**
     * code.generated while a preview runs: new files into the ConfigMap, and a
     * fresh pod in the same namespace - so the address doesn't change.
     */
    @Override
    public void refreshPreview(Long projectId, Map<String, String> files) {
        for (String ns : namespacesOf(projectId)) {
            k8s.replace("/api/v1/namespaces/" + ns + "/configmaps/files", configMap(files));
            k8s.delete("/api/v1/namespaces/" + ns + "/pods/preview");
            createPodWhenGone(ns, files);
        }
    }

    // ------------------------------------------------------------ objects

    private Map<String, Object> namespace(String ns, Long projectId) {
        return Map.of("apiVersion", "v1", "kind", "Namespace", "metadata", Map.of("name", ns, "labels", Map.of(
                "app.kubernetes.io/managed-by", "forgeflow",
                "app.kubernetes.io/part-of", "forgeflow-previews",
                PROJECT_LABEL, String.valueOf(projectId))));
    }

    private static Map<String, Object> quota() {
        return Map.of("apiVersion", "v1", "kind", "ResourceQuota", "metadata", Map.of("name", "quota"),
                "spec", Map.of("hard", Map.of("pods", "2", "limits.cpu", "1", "limits.memory", "1Gi")));
    }

    private Map<String, Object> networkPolicy(boolean react) {
        // Ingress: only the gateway's namespace. Egress: none for a static
        // site; a React dev server needs DNS and HTTPS to reach the npm registry.
        List<Object> egress = react
                ? List.of(Map.of("ports", List.of(Map.of("protocol", "UDP", "port", 53), Map.of("protocol", "TCP", "port", 53))),
                          Map.of("ports", List.of(Map.of("protocol", "TCP", "port", 443))))
                : List.of();
        return Map.of("apiVersion", "networking.k8s.io/v1", "kind", "NetworkPolicy", "metadata", Map.of("name", "preview"),
                "spec", Map.of(
                        "podSelector", Map.of(),
                        "policyTypes", List.of("Ingress", "Egress"),
                        "ingress", List.of(Map.of("from", List.of(Map.of("namespaceSelector",
                                Map.of("matchLabels", Map.of("kubernetes.io/metadata.name", gatewayNamespace)))))),
                        "egress", egress));
    }

    static Map<String, Object> configMap(Map<String, String> files) {
        Map<String, String> data = new LinkedHashMap<>();
        files.forEach((path, content) -> data.put(key(path), content));
        return Map.of("apiVersion", "v1", "kind", "ConfigMap", "metadata", Map.of("name", "files"), "data", data);
    }

    private Map<String, Object> pod(Map<String, String> files, boolean react) {
        // ConfigMap keys can't contain "/": each file is stored under an
        // escaped key and mapped back to its real path in the volume.
        List<Map<String, String>> items = new ArrayList<>();
        files.keySet().forEach(path -> items.add(Map.of("key", key(path), "path", path)));

        Map<String, Object> locked = Map.of(
                "allowPrivilegeEscalation", false,
                "readOnlyRootFilesystem", true,
                "capabilities", Map.of("drop", List.of("ALL")));
        Map<String, Object> container = react
                ? Map.of(
                        "name", "dev",
                        "image", nodeImage,
                        "workingDir", "/app",
                        // The ConfigMap volume is read-only and full of symlinks
                        // (..data/...): copy the files out, drop the ".." dirs.
                        "command", List.of("sh", "-c", "cp -rL /src/. /app/ && rm -rf /app/..?* && "
                                + "npm install --no-audit --no-fund && exec npx vite --host 0.0.0.0 --port 3000 --strictPort"),
                        "env", List.of(Map.of("name", "HOME", "value", "/home/node"),
                                Map.of("name", "npm_config_update_notifier", "value", "false")),
                        "ports", List.of(Map.of("containerPort", 3000)),
                        "volumeMounts", List.of(
                                Map.of("name", "files", "mountPath", "/src", "readOnly", true),
                                Map.of("name", "app", "mountPath", "/app"),
                                Map.of("name", "home", "mountPath", "/home/node"),
                                Map.of("name", "tmp", "mountPath", "/tmp")),
                        "readinessProbe", Map.of("httpGet", Map.of("path", "/", "port", 3000),
                                "periodSeconds", 3, "failureThreshold", 100),
                        "resources", Map.of("requests", Map.of("cpu", "100m", "memory", "256Mi"),
                                "limits", Map.of("cpu", "1", "memory", "768Mi")),
                        "securityContext", withUser(locked, 1000))
                : Map.of(
                        "name", "web",
                        "image", staticImage,
                        "ports", List.of(Map.of("containerPort", 8080)),
                        "volumeMounts", List.of(
                                Map.of("name", "files", "mountPath", "/usr/share/nginx/html", "readOnly", true),
                                Map.of("name", "tmp", "mountPath", "/tmp")),
                        "readinessProbe", Map.of("httpGet", Map.of("path", "/", "port", 8080), "periodSeconds", 1),
                        "resources", Map.of("requests", Map.of("cpu", "25m", "memory", "16Mi"),
                                "limits", Map.of("cpu", "250m", "memory", "64Mi")),
                        "securityContext", withUser(locked, 101));

        List<Map<String, Object>> volumes = new ArrayList<>();
        volumes.add(Map.of("name", "files", "configMap", Map.of("name", "files", "items", items)));
        volumes.add(Map.of("name", "tmp", "emptyDir", Map.of("sizeLimit", "64Mi")));
        if (react) {
            volumes.add(Map.of("name", "app", "emptyDir", Map.of("sizeLimit", "512Mi")));
            volumes.add(Map.of("name", "home", "emptyDir", Map.of("sizeLimit", "256Mi")));
        }
        return Map.of("apiVersion", "v1", "kind", "Pod",
                "metadata", Map.of("name", "preview", "labels", Map.of("app", "preview")),
                "spec", Map.of(
                        "automountServiceAccountToken", false,     // generated code gets no cluster credentials
                        "enableServiceLinks", false,
                        "activeDeadlineSeconds", lifetime.toSeconds(),
                        "securityContext", Map.of("runAsNonRoot", true, "seccompProfile", Map.of("type", "RuntimeDefault")),
                        "containers", List.of(container),
                        "volumes", volumes));
    }

    private static Map<String, Object> withUser(Map<String, Object> base, int uid) {
        Map<String, Object> m = new LinkedHashMap<>(base);
        m.put("runAsUser", uid);
        m.put("runAsGroup", uid);
        return m;
    }

    private static Map<String, Object> service(boolean react) {
        return Map.of("apiVersion", "v1", "kind", "Service", "metadata", Map.of("name", "preview"),
                "spec", Map.of("selector", Map.of("app", "preview"),
                        "ports", List.of(Map.of("port", 80, "targetPort", react ? 3000 : 8080))));
    }

    /** "src/App.jsx" -> "src_2fApp.jsx": every character a ConfigMap key can't hold, as _xx. */
    static String key(String path) {
        StringBuilder sb = new StringBuilder();
        for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_').append(String.format("%02x", b & 0xff));
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ helpers

    private List<String> namespacesOf(Long projectId) {
        JsonNode list = k8s.get("/api/v1/namespaces?" + KubernetesApi.labelSelector(PROJECT_LABEL, String.valueOf(projectId)));
        List<String> out = new ArrayList<>();
        for (JsonNode item : list.path("items")) {
            String name = item.path("metadata").path("name").asText();
            if (name.startsWith(NS_PREFIX)) {
                out.add(name);
            }
        }
        return out;
    }

    private void waitUntilReady(String ns) {
        long deadline = System.nanoTime() + readyTimeout.toNanos();
        String why = "it never became ready";
        while (System.nanoTime() < deadline) {
            JsonNode pod = k8s.get("/api/v1/namespaces/" + ns + "/pods/preview");
            for (JsonNode c : pod.path("status").path("conditions")) {
                if ("Ready".equals(c.path("type").asText()) && "True".equals(c.path("status").asText())) {
                    return;
                }
            }
            for (JsonNode cs : pod.path("status").path("containerStatuses")) {
                JsonNode waiting = cs.path("state").path("waiting");
                String reason = waiting.path("reason").asText("");
                if (reason.equals("ImagePullBackOff") || reason.equals("ErrImagePull") || reason.equals("CrashLoopBackOff")
                        || reason.equals("CreateContainerConfigError")) {
                    throw new SandboxException("The preview pod failed: " + reason + " - " + waiting.path("message").asText(""));
                }
                if (!reason.isEmpty()) {
                    why = "last state: " + reason;
                }
            }
            if ("Failed".equals(pod.path("status").path("phase").asText())) {
                throw new SandboxException("The preview pod failed: " + pod.path("status").path("reason").asText("unknown"));
            }
            sleep(pollEvery);
        }
        throw new SandboxException("The preview pod wasn't ready after " + readyTimeout.toSeconds() + " s (" + why + ")");
    }

    /** A deleted pod lingers while it terminates; the name is free only once it's gone. */
    private void createPodWhenGone(String ns, Map<String, String> files) {
        boolean react = files.containsKey("package.json");
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (true) {
            try {
                k8s.create("/api/v1/namespaces/" + ns + "/pods", pod(files, react));
                return;
            } catch (KubernetesApi.ApiException e) {
                if (e.status != 409 || System.nanoTime() > deadline) {
                    throw new SandboxException("Could not redeploy the preview: " + e.getMessage());
                }
                sleep(pollEvery);
            }
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxException("interrupted while waiting for the preview pod");
        }
    }
}
