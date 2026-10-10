package com.forgeflow.gateway;

import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway in front of two fake services ("core" and "intelligence"),
 * each answering with its own name and echoing what it received.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayTest {

    static final String SECRET = "a-test-secret-that-is-at-least-32-bytes-long";
    static final HttpServer core = fake("core");
    static final HttpServer intelligence = fake("intelligence");
    static final List<Map<String, String>> received = new CopyOnWriteArrayList<>();

    @LocalServerPort
    int port;
    final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry r) {
        r.add("gateway.jwt-secret", () -> SECRET);
        r.add("gateway.upstreams.core", () -> "http://127.0.0.1:" + core.getAddress().getPort());
        r.add("gateway.upstreams.intelligence", () -> "http://127.0.0.1:" + intelligence.getAddress().getPort());
        r.add("gateway.upstreams.execution", () -> "http://127.0.0.1:1");      // nothing listens: "down"
    }

    static HttpServer fake(String name) {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", ex -> {
                Map<String, String> seen = new ConcurrentHashMap<>();
                seen.put("service", name);
                seen.put("path", ex.getRequestURI().toString());
                ex.getRequestHeaders().forEach((k, v) -> seen.put(k.toLowerCase(), String.join(",", v)));
                received.add(seen);
                if (ex.getRequestURI().getPath().endsWith("/stream")) {
                    ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                    ex.sendResponseHeaders(200, 0);
                    try (OutputStream o = ex.getResponseBody()) {
                        o.write("data: first\n\n".getBytes(StandardCharsets.UTF_8));
                        o.flush();
                        Thread.sleep(1500);
                        o.write("data: second\n\n".getBytes(StandardCharsets.UTF_8));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return;
                }
                byte[] body = ("served by " + name).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("X-Auth-Token", "renewed");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        core.stop(0);
        intelligence.stop(0);
    }

    @BeforeEach
    void clear() {
        received.clear();
    }

    String token(Date expires) {
        return Jwts.builder().subject("42").expiration(expires)
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        headers.forEach(b::header);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    String valid() {
        return "Bearer " + token(new Date(System.currentTimeMillis() + 60_000));
    }

    @Test
    void routesByPathToTheRightService() throws Exception {
        HttpResponse<String> chat = get("/api/v1/projects/7/chat/sessions", Map.of("Authorization", valid()));
        assertThat(chat.body()).isEqualTo("served by intelligence");
        assertThat(chat.headers().firstValue("X-Served-By")).hasValue("intelligence");

        HttpResponse<String> me = get("/api/v1/me?x=1", Map.of("Authorization", valid()));
        assertThat(me.body()).isEqualTo("served by core");
        assertThat(received.get(received.size() - 1).get("path")).isEqualTo("/api/v1/me?x=1");
        assertThat(me.headers().firstValue("X-Auth-Token")).hasValue("renewed");    // sliding-session header passes back
    }

    @Test
    void protectedPathsNeedAValidTokenAtTheEdge() throws Exception {
        assertThat(get("/api/v1/projects", Map.of()).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/projects", Map.of("Authorization", "Bearer forged.token.here")).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/projects", Map.of("Authorization",
                "Bearer " + token(new Date(System.currentTimeMillis() - 1000)))).statusCode()).isEqualTo(401);
        assertThat(received).isEmpty();                                  // none of it reached a service

        assertThat(get("/api/v1/projects", Map.of("Authorization", valid())).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/auth/login", Map.of()).statusCode()).isEqualTo(200);          // public
        assertThat(get("/index.html", Map.of()).statusCode()).isEqualTo(200);                  // the workbench
    }

    @Test
    void addsProxyHeadersAndStripsSpoofedOnes() throws Exception {
        get("/api/v1/me", Map.of("Authorization", valid(), "X-User-Id", "1", "X-Forwarded-For", "10.0.0.9"));
        Map<String, String> seen = received.get(0);
        assertThat(seen).doesNotContainKey("x-user-id");
        assertThat(seen.get("x-forwarded-for")).startsWith("10.0.0.9, ");
        assertThat(seen.get("traceparent")).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
        assertThat(seen.get("x-request-id")).isNotBlank();
    }

    @Test
    void streamsServerSentEventsAsTheyArriveNotWhenTheyEnd() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/projects/7/chat/stream"))
                .header("Authorization", valid()).build();
        long start = System.nanoTime();
        HttpResponse<java.io.InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (BufferedReader r = new BufferedReader(new InputStreamReader(res.body(), StandardCharsets.UTF_8))) {
            assertThat(r.readLine()).isEqualTo("data: first");
            long firstMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(firstMs).as("first event arrived before the upstream's 1.5 s pause ended").isLessThan(1200);
            r.readLine();
            assertThat(r.readLine()).isEqualTo("data: second");
        }
    }

    @Test
    void aDownServiceIsA502NotAHang() throws Exception {
        HttpResponse<String> r = get("/p/sometoken/index.html", Map.of());
        assertThat(r.statusCode()).isEqualTo(502);
        assertThat(r.body()).contains("execution");
    }

    @Test
    void theRouteTableIsVisible() throws Exception {
        assertThat(get("/gateway/routes", Map.of()).body()).contains("/api/v1/projects/*/chat/").contains("intelligence");
    }
}
