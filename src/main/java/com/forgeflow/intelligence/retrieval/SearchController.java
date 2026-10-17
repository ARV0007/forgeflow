package com.forgeflow.intelligence.retrieval;

import com.forgeflow.workspace.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

/**
 * Code search over one project - the same search the agent's search_code tool runs. A read.
 * And /ask: a question answered from that search, with citations.
 * {@code mode} picks vector-only or keyword-only instead of hybrid; the retrieval eval uses it.
 */
@RestController
public class SearchController {

    /** Set when the query couldn't be embedded and vector search was skipped. */
    public static final String DEGRADED_HEADER = "X-Search-Degraded";
    /** Set to the number of chunks vector search can't see yet (embedding failed at index time). */
    public static final String MISSING_VECTORS_HEADER = "X-Index-Missing-Vectors";

    /** Set when rerank was asked for but the model call failed; the fused order was returned. */
    public static final String RERANK_FAILED_HEADER = "X-Rerank-Failed";

    private final CodeIndex index;
    private final ProjectService projects;
    private final LlmReranker reranker;
    private final ProjectQa qa;
    private final AgenticRetriever agentic;
    private final com.forgeflow.billing.Entitlements entitlements;
    private final com.forgeflow.billing.UsageMeter usage;

    /** On a mode=agentic search: what it tried, rounds separated by " | ". */
    public static final String TRACE_HEADER = "X-Search-Trace";

    public SearchController(CodeIndex index, ProjectService projects, LlmReranker reranker, ProjectQa qa,
                            AgenticRetriever agentic, com.forgeflow.billing.Entitlements entitlements,
                            com.forgeflow.billing.UsageMeter usage) {
        this.index = index;
        this.projects = projects;
        this.reranker = reranker;
        this.qa = qa;
        this.agentic = agentic;
        this.entitlements = entitlements;
        this.usage = usage;
    }

    /** @param verify also check each cited claim against its sources (one more model call) */
    public record AskRequest(String question, Boolean verify) {
    }

    /**
     * Ask a question about the project's code; the answer comes only from the
     * code, with the files and lines it used. Read access is enough - nothing
     * is written - and the model call is charged to the asker.
     */
    @PostMapping("/api/v1/projects/{projectId}/ask")
    public ProjectQa.Answer ask(@PathVariable Long projectId, @RequestBody AskRequest body, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        projects.getById(projectId, userId);
        String q = body == null || body.question() == null ? "" : body.question().strip();
        if (q.isEmpty() || q.length() > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question must be 1 to 1000 characters");
        }
        return qa.ask(userId, projectId, q, Boolean.TRUE.equals(body.verify()));
    }

    @GetMapping("/api/v1/projects/{projectId}/search")
    public ResponseEntity<List<CodeIndex.SearchHit>> search(@PathVariable Long projectId,
                                            @RequestParam("q") String query,
                                            @RequestParam(defaultValue = "8") int k,
                                            @RequestParam(defaultValue = "hybrid") String mode,
                                            @RequestParam(required = false) Double vectorWeight,
                                            @RequestParam(required = false) Double keywordWeight,
                                            @RequestParam(defaultValue = "false") boolean rerank,
                                            Authentication auth) {
        projects.getById(projectId, (Long) auth.getPrincipal());
        if ("agentic".equalsIgnoreCase(mode)) {
            // Rewrite, search, grade, retry: model calls, so metered like any other.
            Long userId = (Long) auth.getPrincipal();
            entitlements.requireRoomFor(userId, com.forgeflow.billing.Quota.AI_TOKENS_PER_DAY);
            AgenticRetriever.Result r = agentic.search(projectId, query, Math.max(1, Math.min(k, 20)), null);
            usage.record(userId, projectId, com.forgeflow.billing.UsageKind.AI_TOKENS, r.totalTokens(), "agentic-search");
            String trace = String.join(" | ", r.trace()).replaceAll("[\\r\\n]+", " ");
            return ResponseEntity.ok().header(TRACE_HEADER, trace.length() > 1500 ? trace.substring(0, 1500) : trace)
                    .body(r.hits());
        }
        CodeIndex.Mode m;
        try {
            m = CodeIndex.Mode.valueOf(mode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode must be hybrid, vector, keyword or agentic");
        }
        // Weights override the configured fusion for this one search - how the
        // eval sweeps them without a restart. Out of range is a client error.
        CodeIndex.Fusion fusion = null;
        if (vectorWeight != null || keywordWeight != null) {
            // A weight left out keeps the configured one, not 1.0.
            double vw = vectorWeight == null ? index.fusion().vectorWeight() : vectorWeight;
            double kw = keywordWeight == null ? index.fusion().keywordWeight() : keywordWeight;
            if (vw < 0 || kw < 0 || vw > 10 || kw > 10) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "weights must be between 0 and 10");
            }
            fusion = new CodeIndex.Fusion(vw, kw);
        }
        int fetch = rerank ? Math.max(k, LlmReranker.CANDIDATES) : k;
        CodeIndex.SearchResult r = fusion == null
                ? index.searchDetailed(projectId, query, fetch, m)
                : index.searchDetailed(projectId, query, fetch, m, fusion);
        List<CodeIndex.SearchHit> hits = r.hits();
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
        if (rerank) {
            LlmReranker.Reranked rr = reranker.rerank((Long) auth.getPrincipal(), projectId, query, hits);
            hits = rr.hits();
            if (rr.failed()) {
                ok.header(RERANK_FAILED_HEADER, "true");
            }
        }
        hits = hits.subList(0, Math.min(k, hits.size()));
        // Headers, not body fields, so the response stays a plain list for
        // every existing caller. Absent means "fine".
        if (r.degraded()) {
            ok.header(DEGRADED_HEADER, "true");
        }
        if (r.chunksMissingVectors() > 0) {
            ok.header(MISSING_VECTORS_HEADER, String.valueOf(r.chunksMissingVectors()));
        }
        return ok.body(hits);
    }
}
