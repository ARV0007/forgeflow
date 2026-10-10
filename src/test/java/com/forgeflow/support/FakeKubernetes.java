package com.forgeflow.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Just enough of the Kubernetes API server to test the preview provider
 * against: it stores namespaces and the objects created in them, answers a
 * pod as Ready after a couple of polls (or stuck pulling its image, on
 * request), deletes a namespace with everything in it, and records every
 * call so a test can assert what was asked for.
 *
 * Same approach as the Stripe tests: the real protocol, a fake server.
 */
public class FakeKubernetes implements AutoCloseable {

    public record Call(String method, String path, String auth, JsonNode body) {
    }

    private static final Pattern NAMESPACED = Pattern.compile("^/(?:api/v1|apis/[^/]+/v1)/namespaces/([^/]+)/([a-z]+)(?:/([^/?]+))?$");

    private final HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    public final List<Call> calls = new CopyOnWriteArrayList<>();
    /** namespace -> labels */
    public final Map<String, Map<String, String>> namespaces = new ConcurrentHashMap<>();
    /** "ns/kind/name" -> object */
    public final Map<String, JsonNode> objects = new ConcurrentHashMap<>();

    /** What a pod reports while waiting; null = it becomes Ready on the second poll. */
    public volatile String podWaitingReason;
    /** How many pod creates to refuse with 409 first (a pod still terminating). */
    public final AtomicInteger conflictsBeforePodCreate = new AtomicInteger();
    private final Map<String, AtomicInteger> polls = new ConcurrentHashMap<>();

    public FakeKubernetes() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Call> calls(String method, String pathPart) {
        return calls.stream().filter(c -> c.method().equals(method) && c.path().contains(pathPart)).toList();
    }

    public JsonNode object(String ns, String kind, String name) {
        return objects.get(ns + "/" + kind + "/" + name);
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String raw = ex.getRequestURI().getRawPath();
        String query = ex.getRequestURI().getRawQuery();
        byte[] in = ex.getRequestBody().readAllBytes();
        JsonNode body = in.length == 0 ? null : json.readTree(in);
        calls.add(new Call(method, raw + (query == null ? "" : "?" + query),
                ex.getRequestHeaders().getFirst("Authorization"), body));

        if (raw.equals("/api/v1/namespaces")) {
            if (method.equals("POST")) {
                String name = body.path("metadata").path("name").asText();
                Map<String, String> labels = new ConcurrentHashMap<>();
                body.path("metadata").path("labels").properties().forEach(e -> labels.put(e.getKey(), e.getValue().asText()));
                namespaces.put(name, labels);
                reply(ex, 201, body);
                return;
            }
            // GET with labelSelector=k=v
            String selector = query == null ? "" : URLDecoder.decode(query.replace("labelSelector=", ""), StandardCharsets.UTF_8);
            String[] kv = selector.split("=", 2);
            ObjectNode list = json.createObjectNode();
            var items = list.putArray("items");
            namespaces.forEach((name, labels) -> {
                if (kv.length == 2 && kv[1].equals(labels.get(kv[0]))) {
                    items.addObject().putObject("metadata").put("name", name);
                }
            });
            reply(ex, 200, list);
            return;
        }
        if (raw.startsWith("/api/v1/namespaces/") && raw.indexOf('/', "/api/v1/namespaces/".length()) < 0) {
            String name = raw.substring("/api/v1/namespaces/".length());
            if (method.equals("DELETE")) {
                boolean had = namespaces.remove(name) != null;
                objects.keySet().removeIf(k -> k.startsWith(name + "/"));
                reply(ex, had ? 200 : 404, status(had ? "deleted" : "not found"));
                return;
            }
        }
        Matcher m = NAMESPACED.matcher(raw);
        if (m.matches()) {
            String ns = m.group(1);
            String kind = m.group(2);
            if (!namespaces.containsKey(ns)) {
                reply(ex, 404, status("namespaces \"" + ns + "\" not found"));
                return;
            }
            switch (method) {
                case "POST" -> {
                    String name = body.path("metadata").path("name").asText();
                    if (kind.equals("pods") && conflictsBeforePodCreate.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                        reply(ex, 409, status("object is being deleted"));
                        return;
                    }
                    objects.put(ns + "/" + kind + "/" + name, body);
                    reply(ex, 201, body);
                }
                case "PUT" -> {
                    objects.put(ns + "/" + kind + "/" + m.group(3), body);
                    reply(ex, 200, body);
                }
                case "DELETE" -> {
                    reply(ex, objects.remove(ns + "/" + kind + "/" + m.group(3)) != null ? 200 : 404, status("ok"));
                }
                case "GET" -> {
                    JsonNode obj = objects.get(ns + "/" + kind + "/" + m.group(3));
                    if (obj == null) {
                        reply(ex, 404, status("not found"));
                        return;
                    }
                    ObjectNode copy = (ObjectNode) obj.deepCopy();
                    if (kind.equals("pods")) {
                        ObjectNode st = copy.putObject("status");
                        st.put("phase", "Pending");
                        if (podWaitingReason != null) {
                            st.putArray("containerStatuses").addObject().putObject("state").putObject("waiting")
                              .put("reason", podWaitingReason).put("message", "simulated");
                        } else if (polls.computeIfAbsent(ns, k -> new AtomicInteger()).incrementAndGet() >= 2) {
                            st.put("phase", "Running");
                            st.putArray("conditions").addObject().put("type", "Ready").put("status", "True");
                        }
                    }
                    reply(ex, 200, copy);
                }
                default -> reply(ex, 405, status("no"));
            }
            return;
        }
        reply(ex, 404, status("no route " + raw));
    }

    private ObjectNode status(String message) {
        ObjectNode s = json.createObjectNode();
        s.put("kind", "Status").put("message", message);
        return s;
    }

    private void reply(HttpExchange ex, int code, JsonNode body) throws IOException {
        byte[] out = json.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, out.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(out);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
