package com.forgeflow.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findBySessionIdOrderByIdAsc(Long sessionId);

    /** The newest N messages strictly before a given one - the model's memory window. */
    List<ChatMessage> findBySessionIdAndIdLessThanOrderByIdDesc(Long sessionId, Long beforeId, Pageable page);

    Optional<ChatMessage> findFirstBySessionIdOrderByIdDesc(Long sessionId);

    Optional<ChatMessage> findFirstBySessionIdAndRoleAndIdLessThanOrderByIdDesc(Long sessionId, String role, Long beforeId);

    long countBySessionId(Long sessionId);
}
