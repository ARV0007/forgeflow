package com.forgeflow.workspace;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectFileRepository extends JpaRepository<ProjectFile, Long> {

    List<ProjectFile> findByProjectIdOrderByPath(Long projectId);

    Optional<ProjectFile> findByProjectIdAndPath(Long projectId, String path);

    long countByProjectId(Long projectId);
}
