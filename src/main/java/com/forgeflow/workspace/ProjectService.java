package com.forgeflow.workspace;

import com.forgeflow.shared.ResourceNotFoundException;
import com.forgeflow.workspace.dto.CreateProjectRequest;
import com.forgeflow.workspace.dto.ProjectResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class ProjectService {

    private final ProjectRepository projects;

    public ProjectService(ProjectRepository projects) {
        this.projects = projects;
    }

    @Transactional
    public ProjectResponse create(Long ownerId, CreateProjectRequest req) {
        Project p = new Project();
        p.setOwnerId(ownerId);          // server-side, from the JWT
        p.setName(req.name());
        p.setDescription(req.description());
        return ProjectResponse.from(projects.save(p));
    }

    /** Collection: scope the QUERY. Filtering in Java would mean loading rows
     *  the caller is not allowed to see. */
    public List<ProjectResponse> listForOwner(Long ownerId) {
        return projects.findByOwnerIdAndDeletedAtIsNull(ownerId)
                .stream()
                .map(ProjectResponse::from)
                .toList();
    }

    public ProjectResponse getById(Long id, Long ownerId) {
        return ProjectResponse.from(findOwned(id, ownerId));
    }

    @Transactional
    public ProjectResponse update(Long id, Long ownerId, CreateProjectRequest req) {
        Project p = findOwned(id, ownerId);
        p.setName(req.name());
        p.setDescription(req.description());
        return ProjectResponse.from(projects.save(p));
    }

    @Transactional
    public void softDelete(Long id, Long ownerId) {
        Project p = findOwned(id, ownerId);
        p.setDeletedAt(Instant.now());
        projects.save(p);
    }

    /**
     * Ownership lives in ONE place. The owner check is part of the query, so
     * "someone else's project" and "no such project" are the same 404 with the
     * same message - a caller learns nothing by probing IDs.
     */
    private Project findOwned(Long id, Long ownerId) {
        return projects.findByIdAndOwnerIdAndDeletedAtIsNull(id, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
    }
}
