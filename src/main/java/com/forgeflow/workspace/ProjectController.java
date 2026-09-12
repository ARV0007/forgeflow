package com.forgeflow.workspace;

import com.forgeflow.workspace.dto.CreateProjectRequest;
import com.forgeflow.workspace.dto.ProjectResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projects;

    public ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    /** The principal was put here by JwtAuthFilter: it is the user ID. */
    private Long callerId(Authentication auth) {
        return (Long) auth.getPrincipal();
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody CreateProjectRequest req,
                                                  Authentication auth) {
        ProjectResponse created = projects.create(callerId(auth), req);
        // 201 + Location, not a bare 200.
        return ResponseEntity.created(URI.create("/api/v1/projects/" + created.id()))
                .body(created);
    }

    @GetMapping
    public List<ProjectResponse> list(Authentication auth) {
        return projects.listForOwner(callerId(auth));
    }

    @GetMapping("/{id}")
    public ProjectResponse get(@PathVariable Long id, Authentication auth) {
        return projects.getById(id, callerId(auth));
    }

    @PutMapping("/{id}")
    public ProjectResponse update(@PathVariable Long id,
                                  @Valid @RequestBody CreateProjectRequest req,
                                  Authentication auth) {
        return projects.update(id, callerId(auth), req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Authentication auth) {
        projects.softDelete(id, callerId(auth));
        return ResponseEntity.noContent().build();
    }
}
