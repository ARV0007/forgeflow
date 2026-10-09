package com.forgeflow.intelligence.retrieval;

import com.forgeflow.workspace.ProjectService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Code search over one project - the same search the agent's search_code tool runs. A read. */
@RestController
public class SearchController {

    private final CodeIndex index;
    private final ProjectService projects;

    public SearchController(CodeIndex index, ProjectService projects) {
        this.index = index;
        this.projects = projects;
    }

    @GetMapping("/api/v1/projects/{projectId}/search")
    public List<CodeIndex.SearchHit> search(@PathVariable Long projectId,
                                            @RequestParam("q") String query,
                                            @RequestParam(defaultValue = "8") int k,
                                            Authentication auth) {
        projects.getById(projectId, (Long) auth.getPrincipal());
        return index.search(projectId, query, k);
    }
}
