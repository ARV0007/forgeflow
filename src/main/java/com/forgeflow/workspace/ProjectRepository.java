package com.forgeflow.workspace;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findByOwnerIdAndDeletedAtIsNull(Long ownerId);

    Optional<Project> findByIdAndOwnerIdAndDeletedAtIsNull(Long id, Long ownerId);

    Optional<Project> findByIdAndDeletedAtIsNull(Long id);

    long countByOwnerIdAndDeletedAtIsNull(Long ownerId);

    /**
     * Everything this user can open: what they own plus what they were let into.
     * Scoped in the QUERY - filtering in Java would mean loading rows the caller
     * may not see. Public projects they have no relation to are not listed;
     * public means "readable by link", not "in everyone's sidebar".
     */
    @Query("""
            SELECT p FROM Project p
            WHERE p.deletedAt IS NULL
              AND (p.ownerId = :userId
                   OR p.id IN (SELECT m.id.projectId FROM ProjectMember m WHERE m.id.userId = :userId))
            ORDER BY p.updatedAt DESC
            """)
    List<Project> findAccessible(@Param("userId") Long userId);
}
