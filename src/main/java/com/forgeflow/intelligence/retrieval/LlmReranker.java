package com.forgeflow.intelligence.retrieval;

import com.forgeflow.billing.Entitlements;
import com.forgeflow.billing.Quota;
import com.forgeflow.billing.UsageKind;
import com.forgeflow.billing.UsageMeter;
import com.forgeflow.shared.llm.LlmClient;
import com.forgeflow.shared.llm.LlmMessage;
import com.forgeflow.shared.llm.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Second-stage ranking. Hybrid search is fast and recall-oriented: it gets the
 * right chunk into the top 15 almost always, but not always to the top. A
 * reranker reads the question and each candidate together - which neither an
 * embedding nor a keyword index does - and reorders them.
 *
 * Production systems use a small cross-encoder for this. ForgeFlow has no
 * local model, so the chat model does it: one call, every candidate numbered,
 * "return the numbers best first". It costs a model call per search, which is
 * why it is opt-in and measured (evals/run_retrieval_eval.py --rerank) before
 * anything turns it on.
 *
 * Never fatal: any failure returns the candidates in their original order
 * and says so, so a measurement can tell.
 *
 * Metered like any other model call: it needs room in the caller's daily
 * AI-token allowance, and its tokens are recorded against them. Otherwise
 * "rerank=true" on a read endpoint would be free model calls for anyone.
 */
@Component
public class LlmReranker {

    private static final Logger log = LoggerFactory.getLogger(LlmReranker.class);
    static final int CANDIDATES = 15;
    private static final int PREVIEW_LINES = 25;
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    static final String SYSTEM = """
            You rank code excerpts by how directly they answer a developer's question
            about a codebase. The best excerpt is the one the developer would open
            first to find or change the thing they asked about.
            Reply with ONLY a JSON array of the excerpt numbers, best first, e.g. [3, 0, 7].
            Include every number exactly once.""";

    public record Reranked(List<CodeIndex.SearchHit> hits, boolean failed) {
    }

    private final LlmClient llm;
    private final Entitlements entitlements;
    private final UsageMeter usage;

    public LlmReranker(LlmClient llm, Entitlements entitlements, UsageMeter usage) {
        this.llm = llm;
        this.entitlements = entitlements;
        this.usage = usage;
    }

    /** @throws com.forgeflow.shared.QuotaExceededException (402) when the caller is out of AI tokens today */
    public Reranked rerank(Long userId, Long projectId, String question, List<CodeIndex.SearchHit> candidates) {
        if (candidates.size() < 2) {
            return new Reranked(candidates, false);
        }
        entitlements.requireRoomFor(userId, Quota.AI_TOKENS_PER_DAY);
        try {
            LlmResponse r = llm.chat(SYSTEM, List.of(LlmMessage.user(prompt(question, candidates))), List.of());
            usage.record(userId, projectId, UsageKind.AI_TOKENS, r.totalTokens(), "rerank");
            List<Integer> order = parseOrder(r.text(), candidates.size());
            List<CodeIndex.SearchHit> out = new ArrayList<>(candidates.size());
            for (int i : order) {
                out.add(candidates.get(i));
            }
            return new Reranked(out, false);
        } catch (RuntimeException e) {
            log.warn("rerank failed, keeping fused order: {}", e.toString());
            return new Reranked(candidates, true);
        }
    }

    static String prompt(String question, List<CodeIndex.SearchHit> candidates) {
        StringBuilder sb = new StringBuilder("Question: ").append(question).append("\n\nExcerpts:\n");
        for (int i = 0; i < candidates.size(); i++) {
            CodeIndex.SearchHit h = candidates.get(i);
            sb.append("\n[").append(i).append("] ").append(h.path())
              .append(" lines ").append(h.startLine()).append('-').append(h.endLine()).append('\n');
            h.content().lines().limit(PREVIEW_LINES).forEach(line -> sb.append(line).append('\n'));
        }
        return sb.toString();
    }

    /**
     * The numbers in the reply, in order, de-duplicated and range-checked;
     * anything the model left out is appended in its original position, so a
     * sloppy reply degrades to a partial reorder rather than lost results.
     */
    static List<Integer> parseOrder(String text, int n) {
        if (text == null) {
            throw new IllegalStateException("empty rerank reply");
        }
        int open = text.indexOf('[');
        int close = text.indexOf(']', open + 1);
        if (open < 0 || close < 0) {
            throw new IllegalStateException("no JSON array in rerank reply");
        }
        Set<Integer> order = new LinkedHashSet<>();
        Matcher m = NUMBER.matcher(text.substring(open, close));
        while (m.find()) {
            int i = Integer.parseInt(m.group());
            if (i >= 0 && i < n) {
                order.add(i);
            }
        }
        if (order.isEmpty()) {
            throw new IllegalStateException("no usable numbers in rerank reply");
        }
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        return List.copyOf(order);
    }
}
