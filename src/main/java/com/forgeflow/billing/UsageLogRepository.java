package com.forgeflow.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;

public interface UsageLogRepository extends JpaRepository<UsageLog, Long> {

    @Query("""
            select coalesce(sum(u.quantity), 0) from UsageLog u
            where u.userId = :userId and u.kind = :kind and u.createdAt >= :since
            """)
    long sumSince(Long userId, String kind, Instant since);
}
