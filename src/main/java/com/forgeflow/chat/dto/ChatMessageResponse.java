package com.forgeflow.chat.dto;

import java.time.Instant;
import java.util.List;

/**
 * @param status    for assistant replies: SUCCEEDED, FAILED or CAPPED. A failed
 *                  reply is part of the history, and it is what retry retries.
 * @param toolCalls for assistant replies: what the agent changed.
 */
public record ChatMessageResponse(
        Long id,
        Long sessionId,
        String role,
        String content,
        Long authorId,
        List<ToolCallSummary> toolCalls,
        int tokensUsed,
        String status,
        Long runId,
        Instant createdAt) {
}
