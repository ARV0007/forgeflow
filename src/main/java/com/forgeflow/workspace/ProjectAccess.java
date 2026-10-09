package com.forgeflow.workspace;

import com.forgeflow.shared.ForbiddenException;
import com.forgeflow.shared.ResourceNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * THE access check. Every route into a project - REST, streaming, MCP, previews -
 * comes through here, so the rules exist in exactly one place.
 *
 * Two outcomes for "no", and the difference is deliberate:
 *
 *   404  you have no relationship to this project. Identical to "it does not
 *        exist", so probing ids teaches a stranger nothing.
 *   403  you can see it, but your role does not allow this. You already know it
 *        exists, so saying "not allowed" leaks nothing new.
 */
@Component
public class ProjectAccess {

    private final ProjectRepository projects;
    private final ProjectMemberRepository members;

    public ProjectAccess(ProjectRepository projects, ProjectMemberRepository members) {
        this.projects = projects;
        this.members = members;
    }

    /** A project the caller may act on, and the role that let them. */
    public record Grant(Project project, ProjectRole role) {
    }

    @Transactional(readOnly = true)
    public Grant require(Long projectId, Long userId, Permission needed) {
        Project project = projects.findByIdAndDeletedAtIsNull(projectId)
                .orElseThrow(ProjectAccess::notFound);

        ProjectRole role = roleOf(project, userId);
        if (role == null) {
            throw notFound();
        }
        if (!role.allows(needed)) {
            throw new ForbiddenException("Your role on this project (" + role + ") does not allow " + needed);
        }
        return new Grant(project, role);
    }

    /** Null means "no relationship" - the caller should get a 404, not a 403. */
    ProjectRole roleOf(Project project, Long userId) {
        if (userId != null && project.getOwnerId().equals(userId)) {
            return ProjectRole.OWNER;
        }
        if (userId != null) {
            var membership = members.findById(new ProjectMember.Key(project.getId(), userId));
            if (membership.isPresent()) {
                return membership.get().getRole();
            }
        }
        return project.isPublic() ? ProjectRole.PUBLIC : null;
    }

    private static ResourceNotFoundException notFound() {
        // Same message whether the project is missing, deleted, or someone
        // else's - the response must not distinguish them.
        return new ResourceNotFoundException("Project not found");
    }
}
