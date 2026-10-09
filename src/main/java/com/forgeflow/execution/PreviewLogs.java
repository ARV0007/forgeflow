package com.forgeflow.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
 * A bounded ring buffer per project, plus live subscribers (the SSE streams).
 * In memory, which is right for one instance - Render runs one. With several
 * instances this becomes a Redis stream or a Kafka topic keyed by project, so
 * a viewer connected to instance A sees a build that ran on instance B.
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

    private final AtomicLong seq = new AtomicLong();

    /** Access-ordered, so the least recently active project's buffer is the one dropped. */
    private final Map<Long, Deque<Line>> buffers = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Deque<Line>> eldest) {
            return size() > MAX_PROJECTS;
        }
    };

    private final Map<Long, Set<Consumer<Line>>> subscribers = new ConcurrentHashMap<>();
    private final Map<Long, long[]> consoleWindow = new ConcurrentHashMap<>();

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
        Line line = new Line(seq.incrementAndGet(), Instant.now(), source, level, text);

        synchronized (buffers) {
            Deque<Line> buffer = buffers.computeIfAbsent(projectId, k -> new ArrayDeque<>());
            buffer.addLast(line);
            while (buffer.size() > KEEP_PER_PROJECT) {
                buffer.removeFirst();
            }
        }

        // Outside the lock: a slow subscriber must not stall everyone else's logging.
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

    int subscriberCount(Long projectId) {
        Set<Consumer<Line>> set = subscribers.get(projectId);
        return set == null ? 0 : set.size();
    }
}
