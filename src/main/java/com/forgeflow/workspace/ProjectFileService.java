package com.forgeflow.workspace;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The workspace module's public face for file contents.
 *
 * Other modules call THIS, not ProjectFileRepository - that is the module
 * boundary rule from architecture.md section 3, and it is what keeps a future
 * split into separate services a config change rather than a rewrite.
 * (AgentTools predates this class and still uses the repository directly;
 * that is known debt, noted rather than silently tolerated.)
 */
@Service
public class ProjectFileService {

    private final ProjectFileRepository files;

    public ProjectFileService(ProjectFileRepository files) {
        this.files = files;
    }

    /** Every file in the project, path -> content, in path order. */
    @Transactional(readOnly = true)
    public Map<String, String> snapshot(Long projectId) {
        Map<String, String> out = new LinkedHashMap<>();
        for (ProjectFile f : files.findByProjectIdOrderByPath(projectId)) {
            out.put(f.getPath(), f.getContent());
        }
        return out;
    }
}
