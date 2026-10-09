package com.forgeflow.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    List<ChatSession> findByProjectIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long projectId);

    /** Scoped by project too, so a session id from another project is simply not found. */
    Optional<ChatSession> findByIdAndProjectIdAndDeletedAtIsNull(Long id, Long projectId);
}
