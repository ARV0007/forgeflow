package com.forgeflow.workspace;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, ProjectMember.Key> {

    List<ProjectMember> findByIdProjectIdOrderByInvitedAt(Long projectId);

    List<ProjectMember> findByIdUserId(Long userId);
}
