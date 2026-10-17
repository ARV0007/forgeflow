package com.forgeflow.intelligence.retrieval;

import com.forgeflow.intelligence.Models;
import com.forgeflow.shared.llm.LlmMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Agentic retrieval - search that checks its own results and tries again.
 *
 * Plain RAG is one shot: embed the question, take the top k, hope. It fails
 * in two familiar ways. The question is phrased nothing like the code ("where
 * do we stop people ordering too much?" vs MAX_QTY), so the right chunk never
 * makes the list. Or the list fills with near-misses, and the model answers
 * from them anyway.
 *
 * This is the "corrective RAG" loop:
 *   1. REWRITE  the question into a few search queries - identifiers it might
 *               be called, plain descriptions - so one phrasing isn't a single
 *               point of failure
 *   2. SEARCH   with every query (the usual hybrid search) and fuse the lists
 *   3. GRADE    each candidate: does it actually help answer the question?
 *   4. RETRY    once, with different queries, if too little was relevant -
 *               telling the rewriter what was tried and what came back
 *
 * Two model calls per round, three or four in all - so it's used where
 * quality matters more than a second of latency: Ask, the agent's search when
 * it asks for a thorough one, and the benchmark (mode=agentic) that measures
 * whether it's worth it.
 *
 * Never fatal: if rewriting fails, the question itself is the query; if
 * grading fails, the fused order is kept and the result says it wasn't graded.
 */
@Component
public class AgenticRetriever {

    private static final Logger log = LoggerFactory.getLogger(AgenticRetriever.class);
    static final int MIN_RELEVANT = 2;
    private static final int PREVIEW_LINES = 20;

    static final String REWRITE = """
            You write search queries for a code search engine that combines
            keyword search and meaning search over one web project (HTML, CSS,
            JavaScript, React).
            Given a developer's question, write up to 3 short queries that would
            find the code that answers it: likely identifiers (function names,
            variable names, CSS classes, element ids), and plain descriptions of
            what the code does. Do not repeat the question word for word.
            Reply with ONLY JSON: {"queries": ["...", "..."]}""";

    static final String GRADE = """
            You judge search results. Given a developer's question about a
            codebase and numbered code excerpts, decide which excerpts contain
            code that helps answer it - the definition, the handler, the style
            or the data the question is about. An excerpt that only mentions a
            word from the question is not relevant.
            Reply with ONLY JSON: {"relevant": [numbers]} - an empty list if none are.""";

    /**
     * @param hits   the relevant excerpts, best first (or the fused order when grading failed)
     * @param trace  one line per round: queries tried, how many candidates were kept
     * @param graded false when grading failed and the hits are the search's own order
     */
    public record Result(List<CodeIndex.SearchHit> hits, List<String> trace, boolean graded,
                         int promptTokens, int totalTokens, BigDecimal costUsd) {
    }

    private final CodeIndex index;
    private final Models models;
    private final int candidates;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * @param candidates how many fused results each round grades (and the most
     *                   a round can add); results seen in round 1 are never
     *                   graded again in round 2
     */
    public AgenticRetriever(CodeIndex index, Models models,
                            @org.springframework.beans.factory.annotation.Value("${forgeflow.retrieval.agentic.candidates:12}") int candidates) {
        this.index = index;
        this.models = models;
        this.candidates = candidates;
    }

    public Result search(Long projectId, String question, int k, Consumer<String> onFallback) {
        Usage usage = new Usage();
        List<String> trace = new ArrayList<>();
        Map<String, CodeIndex.SearchHit> kept = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> tried = new LinkedHashSet<>();
        boolean graded = true;
        List<CodeIndex.SearchHit> fallbackOrder = List.of();

        for (int round = 1; round <= 2; round++) {
            List<String> queries = rewrite(question, round == 1 ? null : feedback(tried, seen), usage, onFallback);
            if (round == 1) {
                queries.add(0, question);
            }
            queries.removeIf(q -> !tried.add(q.toLowerCase(java.util.Locale.ROOT)));
            if (queries.isEmpty()) {
                break;
            }
            List<CodeIndex.SearchHit> found = fuse(queries, projectId, seen);
            if (round == 1) {
                fallbackOrder = found;
            }
            if (found.isEmpty()) {
                trace.add("searched " + quoted(queries) + " - nothing new found");
                continue;
            }
            List<Integer> relevant = grade(question, found, usage, onFallback);
            if (relevant == null) {
                graded = false;
                trace.add("searched " + quoted(queries) + " - couldn't grade the results, kept the search order");
                break;
            }
            for (int i : relevant) {
                CodeIndex.SearchHit h = found.get(i);
                kept.putIfAbsent(key(h), h);
            }
            trace.add("searched " + quoted(queries) + " - " + relevant.size() + " of " + found.size() + " relevant");
            if (kept.size() >= MIN_RELEVANT) {
                break;
            }
        }
        List<CodeIndex.SearchHit> hits = graded
                ? kept.values().stream().limit(k).toList()
                : fallbackOrder.stream().limit(k).toList();
        return new Result(hits, List.copyOf(trace), graded, usage.prompt, usage.total, usage.cost);
    }

    /** Reciprocal rank fusion over every query's results; chunks already looked at in an earlier round are left out. */
    private List<CodeIndex.SearchHit> fuse(List<String> queries, Long projectId, Set<String> seen) {
        Map<String, Double> score = new HashMap<>();
        Map<String, CodeIndex.SearchHit> byKey = new HashMap<>();
        for (String q : queries) {
            List<CodeIndex.SearchHit> hits = index.search(projectId, q, candidates + seen.size());
            for (int rank = 0; rank < hits.size(); rank++) {
                String key = key(hits.get(rank));
                if (seen.contains(key)) {
                    continue;
                }
                byKey.putIfAbsent(key, hits.get(rank));
                score.merge(key, 1.0 / (60 + rank + 1), Double::sum);
            }
        }
        List<CodeIndex.SearchHit> out = score.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(candidates).map(e -> byKey.get(e.getKey())).toList();
        out.forEach(h -> seen.add(key(h)));
        return out;
    }

    private List<String> rewrite(String question, String feedback, Usage usage, Consumer<String> onFallback) {
        String ask = "QUESTION\n" + question.strip() + (feedback == null ? "" : "\n\n" + feedback);
        try {
            Models.Served s = models.chat(Models.Tier.FAST, REWRITE, List.of(LlmMessage.user(ask)), List.of(), onFallback);
            usage.add(s);
            JsonNode root = readJson(s.response().text());
            List<String> out = new ArrayList<>();
            for (JsonNode q : root.path("queries")) {
                String text = q.asText("").strip();
                if (!text.isEmpty() && text.length() <= 200 && out.size() < 3) {
                    out.add(text);
                }
            }
            return out;
        } catch (RuntimeException e) {
            log.debug("query rewrite failed, searching with the question alone: {}", e.toString());
            return new ArrayList<>();
        }
    }

    /** Indexes (0-based) of the relevant candidates, in rank order; null when grading failed. */
    private List<Integer> grade(String question, List<CodeIndex.SearchHit> candidates, Usage usage,
                                Consumer<String> onFallback) {
        StringBuilder sb = new StringBuilder("QUESTION\n").append(question.strip()).append("\n\nEXCERPTS\n");
        for (int i = 0; i < candidates.size(); i++) {
            CodeIndex.SearchHit h = candidates.get(i);
            sb.append("\n[").append(i + 1).append("] ").append(h.path()).append(" lines ")
              .append(h.startLine()).append('-').append(h.endLine()).append('\n');
            h.content().lines().limit(PREVIEW_LINES).forEach(l -> sb.append(l).append('\n'));
        }
        try {
            Models.Served s = models.chat(Models.Tier.FAST, GRADE, List.of(LlmMessage.user(sb.toString())), List.of(), onFallback);
            usage.add(s);
            JsonNode root = readJson(s.response().text());
            if (!root.has("relevant")) {
                return null;
            }
            Set<Integer> picked = new java.util.TreeSet<>();
            for (JsonNode n : root.path("relevant")) {
                int i = n.asInt(-1) - 1;
                if (i >= 0 && i < candidates.size()) {
                    picked.add(i);
                }
            }
            return new ArrayList<>(picked);
        } catch (RuntimeException e) {
            log.debug("grading failed, keeping the fused order: {}", e.toString());
            return null;
        }
    }

    private String feedback(Set<String> tried, Set<String> seen) {
        Set<String> files = new LinkedHashSet<>();
        seen.forEach(k -> files.add(k.substring(0, k.indexOf('#'))));
        return "These queries found little that helps: " + String.join("; ", tried)
                + ". The results came from: " + String.join(", ", files.stream().limit(10).toList())
                + ". Try different angles - other likely names, the UI text a user would see, the CSS or HTML side.";
    }

    private JsonNode readJson(String text) {
        if (text == null) {
            throw new IllegalStateException("empty reply");
        }
        int a = text.indexOf('{');
        int b = text.lastIndexOf('}');
        if (a < 0 || b <= a) {
            throw new IllegalStateException("no JSON in reply");
        }
        return json.readTree(text.substring(a, b + 1));
    }

    private static String key(CodeIndex.SearchHit h) {
        return h.path() + "#" + h.startLine();
    }

    private static String quoted(List<String> qs) {
        return String.join(", ", qs.stream().map(q -> "'" + q + "'").toList());
    }

    private static final class Usage {
        int prompt;
        int total;
        BigDecimal cost = BigDecimal.ZERO;

        void add(Models.Served s) {
            prompt += s.response().promptTokens();
            total += s.response().totalTokens();
            cost = cost.add(s.costUsd());
        }
    }
}
