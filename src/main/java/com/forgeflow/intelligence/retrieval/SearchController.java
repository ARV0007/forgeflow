package com.forgeflow.intelligence.retrieval;

import com.forgeflow.workspace.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

/**
 * Code search over one project - the same search the agent's search_code tool runs. A read.
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

    public SearchController(CodeIndex index, ProjectService projects, LlmReranker reranker) {
        this.index = index;
        this.projects = projects;
        this.reranker = reranker;
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
        CodeIndex.Mode m;
        try {
            m = CodeIndex.Mode.valueOf(mode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode must be hybrid, vector or keyword");
        }
        // Weights override the configured fusion for this one search - how the
        // eval sweeps them without a restart. Out of range is a client error.
        CodeIndex.Fusion fusion = null;
        if (vectorWeight != null || keywordWeight != null) {
            double vw = vectorWeight == null ? 1.0 : vectorWeight;
            double kw = keywordWeight == null ? 1.0 : keywordWeight;
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
