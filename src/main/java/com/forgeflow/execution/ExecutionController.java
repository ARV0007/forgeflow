package com.forgeflow.execution;

import com.forgeflow.workspace.ProjectFileService;
import com.forgeflow.workspace.ProjectService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class ExecutionController {

    private static final Duration PREVIEW_TTL = Duration.ofMinutes(30);

    private final SandboxProvider sandbox;
    private final ProjectService projects;
    private final ProjectFileService files;
    private final PreviewRepository previews;

    public ExecutionController(SandboxProvider sandbox,
                               ProjectService projects,
                               ProjectFileService files,
                               PreviewRepository previews) {
        this.sandbox = sandbox;
        this.projects = projects;
        this.files = files;
        this.previews = previews;
    }

    @PostMapping("/build")
    public BuildResult build(@PathVariable Long projectId, Authentication auth) {
        projects.getById(projectId, (Long) auth.getPrincipal());   // 404 if not yours
        return sandbox.build(projectId, files.snapshot(projectId));
    }

    @PostMapping("/preview")
    public Map<String, Object> preview(@PathVariable Long projectId, Authentication auth) {
        projects.getById(projectId, (Long) auth.getPrincipal());

        markStopped(projectId);
        PreviewHandle handle = sandbox.startPreview(projectId, files.snapshot(projectId));

        Instant now = Instant.now();
        Preview p = new Preview();
        p.setProjectId(projectId);
        p.setContainerId(handle.containerName());
        p.setPreviewUrl(handle.url());
        p.setStatus("RUNNING");
        p.setStartedAt(now);
        p.setExpiresAt(now.plus(PREVIEW_TTL));
        previews.save(p);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", handle.url());
        body.put("container", handle.containerName());
        body.put("expiresAt", p.getExpiresAt());
        return body;
    }

    @DeleteMapping("/preview")
    public ResponseEntity<Void> stopPreview(@PathVariable Long projectId, Authentication auth) {
        projects.getById(projectId, (Long) auth.getPrincipal());
        sandbox.stopPreview(projectId);
        markStopped(projectId);
        return ResponseEntity.noContent().build();
    }

    private void markStopped(Long projectId) {
        for (Preview running : previews.findByProjectIdAndStatus(projectId, "RUNNING")) {
            running.setStatus("STOPPED");
            previews.save(running);
        }
    }
}
