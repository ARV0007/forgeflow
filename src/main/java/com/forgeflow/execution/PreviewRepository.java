package com.forgeflow.execution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PreviewRepository extends JpaRepository<Preview, Long> {

    List<Preview> findByProjectIdAndStatus(Long projectId, String status);
}
