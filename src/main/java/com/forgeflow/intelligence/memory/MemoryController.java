package com.forgeflow.intelligence.memory;

import com.forgeflow.shared.ResourceNotFoundException;
import com.forgeflow.workspace.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Project memory in the workbench: anyone who can see the project reads it; editors add and delete. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/memories")
public class MemoryController {

    private final ProjectMemory memory;
    private final ProjectService projects;

    public MemoryController(ProjectMemory memory, ProjectService projects) {
        this.memory = memory;
        this.projects = projects;
    }

    public record AddFact(String content) {
    }

    @GetMapping
    public List<ProjectMemory.Fact> list(@PathVariable Long projectId, Authentication auth) {
        projects.getById(projectId, (Long) auth.getPrincipal());
        return memory.list(projectId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectMemory.Fact add(@PathVariable Long projectId, @RequestBody AddFact body, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        projects.requireWrite(projectId, userId);
        return memory.remember(projectId, body == null ? null : body.content(), "user", userId, null).fact();
    }

    @DeleteMapping("/{memoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forget(@PathVariable Long projectId, @PathVariable Long memoryId, Authentication auth) {
        projects.requireWrite(projectId, (Long) auth.getPrincipal());
        if (!memory.forget(projectId, memoryId)) {
            throw new ResourceNotFoundException("No such memory in this project");
        }
    }
}
