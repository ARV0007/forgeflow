package com.forgeflow.chat.dto;

import com.forgeflow.chat.ChatSession;

import java.time.Instant;

public record ChatSessionResponse(
        Long id,
        Long projectId,
        Long userId,
        String title,
        Instant createdAt,
        Instant updatedAt) {

    public static ChatSessionResponse from(ChatSession s) {
        return new ChatSessionResponse(s.getId(), s.getProjectId(), s.getUserId(), s.getTitle(),
                s.getCreatedAt(), s.getUpdatedAt());
    }
}
