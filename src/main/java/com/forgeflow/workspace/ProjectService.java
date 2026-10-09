package com.forgeflow.workspace;

import com.forgeflow.billing.Entitlements;
import com.forgeflow.billing.Quota;
import com.forgeflow.billing.UsageKind;
import com.forgeflow.billing.UsageMeter;
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
    private final Entitlements entitlements;
    private final UsageMeter usage;

    public ProjectService(ProjectRepository projects, ProjectMemberRepository members, ProjectAccess access,
                          Entitlements entitlements, UsageMeter usage) {
        this.projects = projects;
        this.members = members;
        this.access = access;
        this.entitlements = entitlements;
        this.usage = usage;
    }

    /**
     * 402 when the plan's project limit is reached. Checked, then inserted - two
     * creates racing could both pass the check and land one over the limit.
     * Accepted: the cost of one extra project is nothing, and closing the gap
     * properly means a lock per user on every create.
     */
    @Transactional
    public ProjectResponse create(Long ownerId, CreateProjectRequest req) {
        entitlements.requireRoomFor(ownerId, Quota.PROJECTS);
        Project p = new Project();
        p.setOwnerId(ownerId);          // server-side, from the JWT
        p.setName(req.name());
        p.setDescription(req.description());
        Project saved = projects.save(p);
        usage.record(ownerId, saved.getId(), UsageKind.PROJECT_CREATED, 1, null);
        return ProjectResponse.from(saved, ProjectRole.OWNER);
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
