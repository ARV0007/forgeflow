package com.forgeflow.workspace;

import com.forgeflow.shared.ResourceNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Version history. Looking is a read (VIEWER); restoring is a write (EDITOR or owner). */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/checkpoints")
public class CheckpointController {

    private final CheckpointService checkpoints;
    private final ProjectService projects;

    public CheckpointController(CheckpointService checkpoints, ProjectService projects) {
        this.checkpoints = checkpoints;
        this.projects = projects;
    }

    private static Long caller(Authentication auth) {
        return (Long) auth.getPrincipal();
    }

    @GetMapping
    public List<CheckpointService.Summary> list(@PathVariable Long projectId,
                                                @RequestParam(defaultValue = "50") int limit,
                                                Authentication auth) {
        projects.getById(projectId, caller(auth));
        return checkpoints.list(projectId, Math.max(1, Math.min(limit, 200)));
    }

    /** Changes from {@code against} (default: the checkpoint before this one) to this one. */
    @GetMapping("/{checkpointId}/diff")
    public List<CheckpointService.FileChange> diff(@PathVariable Long projectId, @PathVariable long checkpointId,
                                                   @RequestParam(required = false) Long against,
                                                   Authentication auth) {
        projects.getById(projectId, caller(auth));
        return checkpoints.diff(projectId, checkpointId, against);
    }

    @GetMapping("/{checkpointId}/files/content")
    public Map<String, Object> fileAt(@PathVariable Long projectId, @PathVariable long checkpointId,
                                      @RequestParam String path, Authentication auth) {
        projects.getById(projectId, caller(auth));
        return checkpoints.contentAt(projectId, checkpointId, path)
                .map(c -> Map.<String, Object>of("path", path, "content", c))
                .orElseThrow(() -> new ResourceNotFoundException("No " + path + " in checkpoint " + checkpointId));
    }

    @PostMapping("/{checkpointId}/restore")
    public CheckpointService.Summary restore(@PathVariable Long projectId, @PathVariable long checkpointId,
                                             Authentication auth) {
        projects.requireWrite(projectId, caller(auth));
        return checkpoints.restore(projectId, checkpointId, caller(auth));
    }
}
