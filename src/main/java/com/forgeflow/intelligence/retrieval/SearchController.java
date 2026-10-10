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

    private final CodeIndex index;
    private final ProjectService projects;

    public SearchController(CodeIndex index, ProjectService projects) {
        this.index = index;
        this.projects = projects;
    }

    @GetMapping("/api/v1/projects/{projectId}/search")
    public ResponseEntity<List<CodeIndex.SearchHit>> search(@PathVariable Long projectId,
                                            @RequestParam("q") String query,
                                            @RequestParam(defaultValue = "8") int k,
                                            @RequestParam(defaultValue = "hybrid") String mode,
                                            Authentication auth) {
        projects.getById(projectId, (Long) auth.getPrincipal());
        CodeIndex.Mode m;
        try {
            m = CodeIndex.Mode.valueOf(mode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode must be hybrid, vector or keyword");
        }
        CodeIndex.SearchResult r = index.searchDetailed(projectId, query, k, m);
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
        // Headers, not body fields, so the response stays a plain list for
        // every existing caller. Absent means "fine".
        if (r.degraded()) {
            ok.header(DEGRADED_HEADER, "true");
        }
        if (r.chunksMissingVectors() > 0) {
            ok.header(MISSING_VECTORS_HEADER, String.valueOf(r.chunksMissingVectors()));
        }
        return ok.body(r.hits());
    }
}
