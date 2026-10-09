package com.forgeflow.workspace;

import com.forgeflow.workspace.dto.CreateProjectRequest;
import com.forgeflow.workspace.dto.ProjectResponse;
import com.forgeflow.workspace.dto.UpdateProjectRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The workspace module's public face for projects. Other modules call
 * getById / require / requireWrite here and never touch ProjectAccess or the
 * repositories directly.
 */
@Service
public class ProjectService {

    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final ProjectAccess access;

    public ProjectService(ProjectRepository projects, ProjectMemberRepository members, ProjectAccess access) {
        this.projects = projects;
        this.members = members;
        this.access = access;
    }

    @Transactional
    public ProjectResponse create(Long ownerId, CreateProjectRequest req) {
        Project p = new Project();
        p.setOwnerId(ownerId);          // server-side, from the JWT
        p.setName(req.name());
        p.setDescription(req.description());
        return ProjectResponse.from(projects.save(p), ProjectRole.OWNER);
    }

    /** Owned plus shared-with-me, each tagged with the caller's role. */
    @Transactional(readOnly = true)
    public List<ProjectResponse> listAccessible(Long userId) {
        Map<Long, ProjectRole> memberRoles = new HashMap<>();
        for (ProjectMember m : members.findByIdUserId(userId)) {
            memberRoles.put(m.getProjectId(), m.getRole());
        }
        return projects.findAccessible(userId).stream()
                .map(p -> ProjectResponse.from(p,
                        p.getOwnerId().equals(userId) ? ProjectRole.OWNER : memberRoles.get(p.getId())))
                .toList();
    }

    /** READ access, or 404. Used by every module that only needs to look. */
    public ProjectResponse getById(Long id, Long userId) {
        ProjectAccess.Grant g = access.require(id, userId, Permission.READ);
        return ProjectResponse.from(g.project(), g.role());
    }

    /** WRITE access: generating, chatting, building, previewing. 404 or 403 otherwise. */
    public ProjectResponse requireWrite(Long id, Long userId) {
        ProjectAccess.Grant g = access.require(id, userId, Permission.WRITE);
        return ProjectResponse.from(g.project(), g.role());
    }

    public ProjectRole roleOf(Long id, Long userId) {
        return access.require(id, userId, Permission.READ).role();
    }

    @Transactional
    public ProjectResponse update(Long id, Long userId, UpdateProjectRequest req) {
        Project p = access.require(id, userId, Permission.ADMIN).project();
        p.setName(req.name());
        p.setDescription(req.description());
        if (req.isPublic() != null) {
            p.setPublic(req.isPublic());
        }
        if (req.thumbnailUrl() != null) {
            p.setThumbnailUrl(req.thumbnailUrl().isBlank() ? null : req.thumbnailUrl());
        }
        return ProjectResponse.from(projects.save(p), ProjectRole.OWNER);
    }

    @Transactional
    public void softDelete(Long id, Long userId) {
        Project p = access.require(id, userId, Permission.ADMIN).project();
        p.setDeletedAt(Instant.now());
        projects.save(p);
    }
}
