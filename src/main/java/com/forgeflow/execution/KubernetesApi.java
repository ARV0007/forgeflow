package com.forgeflow.execution;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;

/**
 * The few Kubernetes API calls ForgeFlow needs, over plain HTTPS.
 *
 * No client library - same reason as the Redis, Stripe and S3 clients: the
 * protocol is small, and seeing it is the point. Every Kubernetes object is a
 * JSON document at a REST path:
 *
 *   POST   /api/v1/namespaces                          create a namespace
 *   POST   /api/v1/namespaces/{ns}/pods                create a pod in it
 *   GET    /api/v1/namespaces/{ns}/pods/{name}         read it (status included)
 *   DELETE /api/v1/namespaces/{ns}                     delete it and everything inside
 *   GET    /api/v1/namespaces?labelSelector=k%3Dv      find by label
 *
 * In a pod, the credentials are mounted for you: a bearer token and the
 * cluster's CA certificate under /var/run/secrets/kubernetes.io/serviceaccount,
 * and the API server's address in KUBERNETES_SERVICE_HOST / _PORT.
 */
class KubernetesApi {

    static final String SA_DIR = "/var/run/secrets/kubernetes.io/serviceaccount";

    /** A non-2xx answer, with the status Kubernetes gave (409 = already exists, 404 = not found). */
    static class ApiException extends RuntimeException {
        final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final String base;
    private final String token;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    KubernetesApi(String base, String token, HttpClient http) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.token = token;
        this.http = http;
    }

    /**
     * From explicit settings, or - with none - from the pod's own service
     * account, the way every in-cluster client finds the API server.
     */
    static KubernetesApi connect(String apiUrl, String token) {
        try {
            if (apiUrl != null && !apiUrl.isBlank()) {
                return new KubernetesApi(apiUrl, token,
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
            }
            String host = System.getenv("KUBERNETES_SERVICE_HOST");
            String port = System.getenv().getOrDefault("KUBERNETES_SERVICE_PORT", "443");
            if (host == null) {
                throw new IllegalStateException("Not running in Kubernetes and no forgeflow.sandbox.kubernetes.api-url set");
            }
            String saToken = Files.readString(Path.of(SA_DIR, "token")).trim();
            return new KubernetesApi("https://" + host + ":" + port, saToken, HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .sslContext(trusting(Path.of(SA_DIR, "ca.crt")))
                    .build());
        } catch (IOException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Could not read the pod's service account: " + e.getMessage(), e);
        }
    }

    /** Trust exactly the cluster's CA - not the JVM's public roots. */
    private static SSLContext trusting(Path caFile) throws IOException, java.security.GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        try (InputStream in = Files.newInputStream(caFile)) {
            int i = 0;
            for (var cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                ks.setCertificateEntry("k8s-ca-" + i++, (X509Certificate) cert);
            }
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    JsonNode create(String path, Object body) {
        return send("POST", path, json.writeValueAsString(body));
    }

    JsonNode replace(String path, Object body) {
        return send("PUT", path, json.writeValueAsString(body));
    }

    JsonNode get(String path) {
        return send("GET", path, null);
    }

    /** Deleting something already gone is fine: the goal state is "not there". */
    void delete(String path) {
        try {
            send("DELETE", path, null);
        } catch (ApiException e) {
            if (e.status != 404) {
                throw e;
            }
        }
    }

    static String labelSelector(String key, String value) {
        return "labelSelector=" + URLEncoder.encode(key + "=" + value, StandardCharsets.UTF_8);
    }

    private JsonNode send(String method, String path, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if (body != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                // Kubernetes errors are a Status object with a readable message.
                String message = r.body();
                try {
                    message = json.readTree(r.body()).path("message").asText(r.body());
                } catch (RuntimeException ignored) {
                    // not JSON - keep the raw body
                }
                throw new ApiException(r.statusCode(), method + " " + path + " -> " + r.statusCode() + ": " + message);
            }
            return r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
        } catch (IOException e) {
            throw new ApiException(0, method + " " + path + " failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(0, method + " " + path + " interrupted");
        }
    }
}
