package com.forgeflow.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every request that isn't the gateway's own (/gateway/*, /actuator/*) is
 * proxied: routed by path, checked at the edge, forwarded with standard
 * proxy headers, and the response streamed back as it arrives.
 *
 * Streaming is the subtle part. A chat reply or a logs stream is
 * Server-Sent Events: the upstream holds the response open and writes a few
 * bytes at a time. Buffering the body - what a naive proxy does - would hold
 * every event until the stream ends, i.e. forever. So the body is copied
 * through in small reads with a flush after each one.
 */
public class ProxyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ProxyFilter.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Hop-by-hop headers (RFC 9110 §7.6.1) plus the ones java.net.http sets itself. */
    private static final Set<String> NOT_FORWARDED = Set.of("connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length",
            "expect", "x-forwarded-for", "x-forwarded-proto", "x-forwarded-host", "x-user-id");
    private static final Set<String> NOT_RETURNED = Set.of("connection", "keep-alive", "transfer-encoding",
            "trailer", "upgrade", "content-length", ":status");

    private final RouteTable routes;
    private final EdgeAuth auth;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public ProxyFilter(RouteTable routes, EdgeAuth auth) {
        this.routes = routes;
        this.auth = auth;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/gateway/") || path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        long started = System.nanoTime();
        String path = req.getRequestURI();
        String requestId = Optional.ofNullable(req.getHeader("X-Request-Id")).orElseGet(() -> hex(8));
        res.setHeader("X-Request-Id", requestId);

        RouteTable.Match route = routes.match(path);
        if (route == null) {
            problem(res, 404, "No route for " + path);
            return;
        }
        if (!EdgeAuth.isPublic(req.getMethod(), path) && auth.verify(req.getHeader("Authorization")).isEmpty()) {
            res.setHeader("WWW-Authenticate", "Bearer");
            problem(res, 401, "A valid bearer token is required");
            access(req, route, 401, started, requestId);
            return;
        }

        String query = req.getQueryString();
        URI target = URI.create(route.upstream() + path + (query == null ? "" : "?" + query));
        HttpRequest.Builder out = HttpRequest.newBuilder(target).timeout(Duration.ofMinutes(30));
        for (String name : Collections.list(req.getHeaderNames())) {
            if (!NOT_FORWARDED.contains(name.toLowerCase(Locale.ROOT))) {
                for (String value : Collections.list(req.getHeaders(name))) {
                    out.header(name, value);
                }
            }
        }
        String clientIp = req.getRemoteAddr();
        String priorChain = req.getHeader("X-Forwarded-For");
        out.header("X-Forwarded-For", priorChain == null ? clientIp : priorChain + ", " + clientIp);
        out.header("X-Forwarded-Proto", req.isSecure() ? "https" : "http");
        out.header("X-Forwarded-Host", Optional.ofNullable(req.getHeader("Host")).orElse(""));
        out.header("X-Request-Id", requestId);
        if (req.getHeader("traceparent") == null) {
            out.header("traceparent", "00-" + hex(16) + "-" + hex(8) + "-01");   // start the trace at the edge
        }
        byte[] body = req.getInputStream().readAllBytes();
        out.method(req.getMethod(), body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));

        HttpResponse<InputStream> upstream;
        try {
            upstream = http.send(out.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (ConnectException e) {
            problem(res, 502, "Service " + route.service() + " is unreachable");
            access(req, route, 502, started, requestId);
            return;
        } catch (HttpTimeoutException e) {
            problem(res, 504, "Service " + route.service() + " did not answer in time");
            access(req, route, 504, started, requestId);
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        res.setStatus(upstream.statusCode());
        for (Map.Entry<String, List<String>> h : upstream.headers().map().entrySet()) {
            if (!NOT_RETURNED.contains(h.getKey().toLowerCase(Locale.ROOT))) {
                for (String v : h.getValue()) {
                    res.addHeader(h.getKey(), v);
                }
            }
        }
        res.setHeader("X-Served-By", route.service());
        boolean stream = upstream.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream");
        try (InputStream in = upstream.body()) {
            OutputStream o = res.getOutputStream();
            res.flushBuffer();                              // headers out now: the client sees the stream open
            byte[] buf = new byte[stream ? 1024 : 16 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                o.write(buf, 0, n);
                if (stream) {
                    o.flush();                               // each event as it arrives, not when the stream ends
                }
            }
            o.flush();
        } catch (IOException e) {
            log.debug("client or upstream went away mid-response on {}: {}", path, e.toString());
        }
        access(req, route, upstream.statusCode(), started, requestId);
    }

    private static void access(HttpServletRequest req, RouteTable.Match route, int status, long started, String id) {
        log.info("{} {} -> {} {} {}ms [{}]", req.getMethod(), req.getRequestURI(), route.service(), status,
                (System.nanoTime() - started) / 1_000_000, id);
    }

    private static void problem(HttpServletResponse res, int status, String detail) throws IOException {
        res.setStatus(status);
        res.setContentType("application/problem+json");
        res.getWriter().write("{\"status\":" + status + ",\"detail\":\"" + detail.replace("\"", "'") + "\"}");
    }

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
