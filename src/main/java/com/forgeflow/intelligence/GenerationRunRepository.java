package com.forgeflow.intelligence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GenerationRunRepository extends JpaRepository<GenerationRun, Long> {

    List<GenerationRun> findByProjectIdOrderByCreatedAtDesc(Long projectId);
}
