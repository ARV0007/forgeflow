package com.forgeflow.execution;

import com.forgeflow.shared.redis.RespClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Spec: Preview - "Logs Stream". What a developer would see in the terminal
 * running their dev server, collected per project:
 *
 *   build    the build gate - started, passed, and each problem it found
 *   preview  preview started / stopped
 *   http     every file the preview served, and every 404 for a missing one
 *   console  console.log / warn / error and uncaught exceptions from the
 *            generated app itself, running in the visitor's browser
 *
 * A bounded buffer per project, plus live subscribers (the SSE streams).
 *
 * Two modes, same API:
 *
 *   memory  (no REDIS_URL) a ring buffer per project in this JVM. Right for one
 *           instance - which is what the free Render deploy runs.
 *   redis   every instance shares one log, so a viewer connected to instance A
 *           sees a build that ran on instance B, and a console line that a
 *           browser reported to B:
 *             seq      INCR ff:logs:seq - one global, rising sequence
 *             buffer   RPUSH + LTRIM ff:logs:{project} (newest 500, a day's expiry)
 *             fan-out  PUBLISH ff:logs:ch:{project}; each instance holds one
 *                      PSUBSCRIBE ff:logs:ch:* and hands lines to its own SSE
 *                      subscribers. An instance's own lines come back the same
 *                      way, so nobody gets a line twice.
 *           The buffer is the record; pub/sub only says "there's a new line".
 *           A subscriber that missed one (a dropped connection) gets it on the
 *           browser's reconnect, which replays from the buffer by sequence.
 *           If Redis is unreachable a line is still delivered locally.
 */
@Component
public class PreviewLogs {

    private static final Logger log = LoggerFactory.getLogger(PreviewLogs.class);

    public record Line(long seq, Instant at, String source, String level, String message) {
    }

    static final int KEEP_PER_PROJECT = 500;
    static final int MAX_PROJECTS = 200;
    static final int MAX_MESSAGE = 2_000;

    /** Console lines arrive from strangers' browsers, so they are rate-capped per project. */
    static final int CONSOLE_PER_MINUTE = 120;

    /**
     * Seeded from the clock (microseconds since the epoch), not zero.
     *
     * Sequence numbers double as the SSE event id, and a reconnecting browser
     * asks for "everything after the last id I saw". Starting at zero meant a
     * restart - every deploy - handed out 1, 2, 3 again: lower than what the
     * browser had already seen, so the browser filtered out every new line
     * and the stream looked dead. Found on the live site after a deploy.
     * Microseconds keep numbers rising across restarts and stay well inside
     * JavaScript's exact-integer range.
     */
    private final AtomicLong seq = new AtomicLong(System.currentTimeMillis() * 1000);

    /** Access-ordered, so the least recently active project's buffer is the one dropped. */
    private final Map<Long, Deque<Line>> buffers = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Deque<Line>> eldest) {
            return size() > MAX_PROJECTS;
        }
    };

    private final Map<Long, Set<Consumer<Line>>> subscribers = new ConcurrentHashMap<>();
    private final Map<Long, long[]> consoleWindow = new ConcurrentHashMap<>();

    /** Redis mode - one push, trim, expire and publish, as a single step. */
    static final String PUSH = "redis.call('RPUSH', KEYS[1], ARGV[1]) "
            + "redis.call('LTRIM', KEYS[1], -tonumber(ARGV[2]), -1) "
            + "redis.call('EXPIRE', KEYS[1], 86400) "
            + "return redis.call('PUBLISH', ARGV[3], ARGV[1])";

    private final RespClient redis;
    private final String prefix;
    private final ObjectMapper json = new ObjectMapper();
    private RespClient.Subscription subscription;

    /** Memory mode. */
    public PreviewLogs() {
        this(null, "ff:logs:");
    }

    @Autowired
    public PreviewLogs(ObjectProvider<RespClient> redis) {
        this(redis.getIfAvailable(), "ff:logs:");
    }

    PreviewLogs(RespClient redis, String prefix) {
        this.redis = redis;
        this.prefix = prefix;
        if (redis == null) {
            return;
        }
        try {
            // Seed the shared counter above anything handed out in memory mode,
            // for the same reason the local one starts at the clock (see seq).
            redis.call("SET", prefix + "seq", Long.toString(seq.get()), "NX");
        } catch (IOException | RuntimeException e) {
            log.warn("could not seed the shared log sequence: {}", e.toString());
        }
        subscription = redis.psubscribe(prefix + "ch:*", this::received);
    }

    /** Redis mode: wait until this instance is hearing other instances' lines. */
    boolean awaitShared(long timeout, TimeUnit unit) throws InterruptedException {
        return subscription == null || subscription.awaitReady(timeout, unit);
    }

    /** Stop listening to Redis (on shutdown, and in tests). */
    @PreDestroy
    public void close() {
        if (subscription != null) {
            subscription.close();
        }
    }

    public void info(Long projectId, String source, String message) {
        append(projectId, source, "info", message);
    }

    public void warn(Long projectId, String source, String message) {
        append(projectId, source, "warn", message);
    }

    public void error(Long projectId, String source, String message) {
        append(projectId, source, "error", message);
    }

    /**
     * A line reported by the generated app running in someone's browser.
     *
     * @return false if dropped by the rate cap
     */
    public boolean fromConsole(Long projectId, String level, String message) {
        long minute = System.currentTimeMillis() / 60_000;
        long[] window = consoleWindow.computeIfAbsent(projectId, k -> new long[2]);
        synchronized (window) {
            if (window[0] != minute) {
                window[0] = minute;
                window[1] = 0;
            }
            if (++window[1] > CONSOLE_PER_MINUTE) {
                return false;
            }
        }
        String normalised = switch (level == null ? "" : level) {
            case "error", "warn", "info" -> level;
            default -> "info";      // console.log, console.debug, anything unexpected
        };
        append(projectId, "console", normalised, message);
        return true;
    }

    public void append(Long projectId, String source, String level, String message) {
        String text = message == null ? "" : message;
        if (text.length() > MAX_MESSAGE) {
            text = text.substring(0, MAX_MESSAGE) + " ...(truncated)";
        }
        if (redis != null && appendShared(projectId, source, level, text)) {
            return;                  // it comes back through the subscription, to everyone
        }
        Line line = new Line(seq.incrementAndGet(), Instant.now(), source, level, text);
        if (redis == null) {
            remember(projectId, line);
        }
        deliver(projectId, line);
    }

    private void remember(Long projectId, Line line) {
        synchronized (buffers) {
            Deque<Line> buffer = buffers.computeIfAbsent(projectId, k -> new ArrayDeque<>());
            buffer.addLast(line);
            while (buffer.size() > KEEP_PER_PROJECT) {
                buffer.removeFirst();
            }
        }
    }

    /** @return false if Redis failed, so the caller delivers the line locally instead. */
    private boolean appendShared(Long projectId, String source, String level, String text) {
        try {
            long n = (Long) redis.call("INCR", prefix + "seq");
            ObjectNode node = json.createObjectNode();
            node.put("p", projectId).put("seq", n).put("at", System.currentTimeMillis())
                .put("source", source).put("level", level).put("message", text);
            redis.call("EVAL", PUSH, "1", prefix + projectId, json.writeValueAsString(node),
                    Integer.toString(KEEP_PER_PROJECT), prefix + "ch:" + projectId);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("preview log to Redis failed ({}); delivering on this instance only", e.toString());
            return false;
        }
    }

    /** A line published by any instance (this one included). */
    private void received(String channel, String payload) {
        JsonNode n = json.readTree(payload);
        deliver(n.path("p").asLong(), lineOf(n));
    }

    private static Line lineOf(JsonNode n) {
        return new Line(n.path("seq").asLong(), Instant.ofEpochMilli(n.path("at").asLong()),
                n.path("source").asText(), n.path("level").asText(), n.path("message").asText());
    }

    private void deliver(Long projectId, Line line) {
        // Outside any lock: a slow subscriber must not stall everyone else's logging.
        Set<Consumer<Line>> listeners = subscribers.get(projectId);
        if (listeners != null) {
            for (Consumer<Line> listener : listeners) {
                try {
                    listener.accept(line);
                } catch (RuntimeException e) {
                    log.debug("dropping a log subscriber for project {}: {}", projectId, e.toString());
                    listeners.remove(listener);
                }
            }
        }
    }

    /** Buffered lines newer than {@code afterSeq} (0 for everything kept). */
    public List<Line> since(Long projectId, long afterSeq) {
        if (redis != null) {
            try {
                Object raw = redis.call("LRANGE", prefix + projectId, "0", "-1");
                List<Line> out = new ArrayList<>();
                if (raw instanceof List<?> items) {
                    for (Object item : items) {
                        Line l = lineOf(json.readTree(String.valueOf(item)));
                        if (l.seq() > afterSeq) {
                            out.add(l);
                        }
                    }
                }
                return out;
            } catch (IOException | RuntimeException e) {
                log.warn("reading preview logs from Redis failed: {}", e.toString());
                return List.of();
            }
        }
        synchronized (buffers) {
            Deque<Line> buffer = buffers.get(projectId);
            if (buffer == null) {
                return List.of();
            }
            List<Line> out = new ArrayList<>();
            for (Line l : buffer) {
                if (l.seq() > afterSeq) {
                    out.add(l);
                }
            }
            return out;
        }
    }

    /**
     * Receive every line appended from now on. A listener that throws is
     * unsubscribed - that is how a closed browser tab cleans itself up.
     *
     * @return call to unsubscribe
     */
    public Runnable subscribe(Long projectId, Consumer<Line> listener) {
        Set<Consumer<Line>> set = subscribers.computeIfAbsent(projectId, k -> ConcurrentHashMap.newKeySet());
        set.add(listener);
        return () -> set.remove(listener);
    }

    /** The newest sequence number handed out (across instances, in Redis mode). */
    public long lastSeq() {
        if (redis != null) {
            try {
                Object v = redis.call("GET", prefix + "seq");
                if (v != null) {
                    return Long.parseLong(String.valueOf(v));
                }
            } catch (IOException | RuntimeException e) {
                log.warn("reading the shared log sequence failed: {}", e.toString());
            }
        }
        return seq.get();
    }

    int subscriberCount(Long projectId) {
        Set<Consumer<Line>> set = subscribers.get(projectId);
        return set == null ? 0 : set.size();
    }
}
