package com.forgeflow.workspace;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Read-only view of what the agent produced. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/files")
public class FileController {

    private final ProjectFileRepository files;
    private final ProjectService projects;

    public FileController(ProjectFileRepository files, ProjectService projects) {
        this.files = files;
        this.projects = projects;
    }

    @GetMapping
    public List<Map<String, Object>> tree(@PathVariable Long projectId, Authentication auth) {
        projects.getById(projectId, (Long) auth.getPrincipal());
        return files.findByProjectIdOrderByPath(projectId).stream()
                .map(f -> Map.<String, Object>of(
                        "path", f.getPath(),
                        "sizeBytes", f.getSizeBytes(),
                        "version", f.getVersion(),
                        "updatedAt", f.getUpdatedAt()))
                .toList();
    }

    @GetMapping("/content")
    public Map<String, Object> content(@PathVariable Long projectId,
                                       @RequestParam String path,
                                       Authentication auth) {
        projects.getById(projectId, (Long) auth.getPrincipal());
        return files.findByProjectIdAndPath(projectId, path)
                .map(f -> Map.<String, Object>of("path", f.getPath(), "content", f.getContent()))
                .orElseThrow(() -> new com.forgeflow.shared.ResourceNotFoundException("File not found: " + path));
    }
}
