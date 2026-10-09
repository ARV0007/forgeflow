package com.forgeflow.workspace;

import com.forgeflow.account.UserDirectory;
import com.forgeflow.account.dto.UserSummary;
import com.forgeflow.shared.ForbiddenException;
import com.forgeflow.shared.ResourceNotFoundException;
import com.forgeflow.workspace.dto.MemberResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Spec: "Members - one project to have many users."
 *
 * Only the owner manages membership. A member may always remove themselves -
 * leaving should never need anyone's permission.
 */
@Service
public class MemberService {

    private final ProjectAccess access;
    private final ProjectMemberRepository members;
    private final UserDirectory users;

    public MemberService(ProjectAccess access, ProjectMemberRepository members, UserDirectory users) {
        this.access = access;
        this.members = members;
        this.users = users;
    }

    /** Anyone who can see the project can see who else is in it. Owner first. */
    @Transactional(readOnly = true)
    public List<MemberResponse> list(Long projectId, Long callerId) {
        Project project = access.require(projectId, callerId, Permission.READ).project();
        List<ProjectMember> rows = members.findByIdProjectIdOrderByInvitedAt(projectId);

        List<Long> ids = new ArrayList<>();
        ids.add(project.getOwnerId());
        rows.forEach(m -> ids.add(m.getUserId()));
        Map<Long, UserSummary> people = users.findByIds(ids);   // one query, not one per row

        List<MemberResponse> out = new ArrayList<>();
        UserSummary owner = people.get(project.getOwnerId());
        out.add(response(owner, project.getOwnerId(), ProjectRole.OWNER, null, project.getCreatedAt()));
        for (ProjectMember m : rows) {
            out.add(response(people.get(m.getUserId()), m.getUserId(), m.getRole(), m.getInvitedBy(), m.getInvitedAt()));
        }
        return out;
    }

    @Transactional
    public MemberResponse add(Long projectId, Long callerId, String email, ProjectRole role) {
        Project project = access.require(projectId, callerId, Permission.ADMIN).project();
        requireAssignable(role);

        // Trade-off, stated: an explicit "no such account" lets an owner learn
        // whether an email is registered. Inviting needs that answer to be
        // useful, the caller is authenticated, and invites are rate limited.
        UserSummary invitee = users.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("No ForgeFlow account with that email"));

        if (invitee.id().equals(project.getOwnerId())) {
            throw new IllegalStateException("That user already owns this project");
        }
        ProjectMember.Key key = new ProjectMember.Key(projectId, invitee.id());
        if (members.existsById(key)) {
            throw new IllegalStateException("That user is already a member - change their role instead");
        }

        ProjectMember saved = members.save(ProjectMember.of(projectId, invitee.id(), role, callerId));
        return response(invitee, invitee.id(), saved.getRole(), saved.getInvitedBy(), saved.getInvitedAt());
    }

    @Transactional
    public MemberResponse changeRole(Long projectId, Long callerId, Long memberUserId, ProjectRole role) {
        access.require(projectId, callerId, Permission.ADMIN);
        requireAssignable(role);
        ProjectMember m = members.findById(new ProjectMember.Key(projectId, memberUserId))
                .orElseThrow(() -> new ResourceNotFoundException("Not a member of this project"));
        m.setRole(role);
        ProjectMember saved = members.save(m);
        UserSummary who = users.findById(memberUserId).orElse(null);
        return response(who, memberUserId, saved.getRole(), saved.getInvitedBy(), saved.getInvitedAt());
    }

    @Transactional
    public void remove(Long projectId, Long callerId, Long memberUserId) {
        boolean leaving = callerId.equals(memberUserId);
        // Leaving needs only to be able to see the project; removing someone
        // else needs to own it.
        access.require(projectId, callerId, leaving ? Permission.READ : Permission.ADMIN);

        ProjectMember.Key key = new ProjectMember.Key(projectId, memberUserId);
        if (!members.existsById(key)) {
            throw new ResourceNotFoundException("Not a member of this project");
        }
        members.deleteById(key);
    }

    private static void requireAssignable(ProjectRole role) {
        if (role == null || !role.isAssignable()) {
            throw new ForbiddenException("Members can be EDITOR or VIEWER; ownership is not transferable here");
        }
    }

    private static MemberResponse response(UserSummary u, Long userId, ProjectRole role, Long invitedBy,
                                           java.time.Instant at) {
        return new MemberResponse(userId,
                u == null ? null : u.email(),
                u == null ? null : u.name(),
                u == null ? null : u.avatarUrl(),
                role, invitedBy, at);
    }
}
