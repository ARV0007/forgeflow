package com.forgeflow.execution;

import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface PreviewRepository extends JpaRepository<Preview, Long> {

    List<Preview> findByProjectIdAndStatus(Long projectId, String status);

    /** Used to resolve a preview link. The token lives in container_id. */
    List<Preview> findByContainerIdAndStatus(String containerId, String status);

    /** Live previews someone started - optionally only in one project. */
    @Query("""
            select count(p) from Preview p
            where p.startedBy = :userId and p.status = 'RUNNING'
              and (p.expiresAt is null or p.expiresAt > :now)
              and (:projectId is null or p.projectId = :projectId)
            """)
    long countLive(Long userId, Long projectId, Instant now);
}
