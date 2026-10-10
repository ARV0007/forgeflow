package com.forgeflow.chat.dto;

import java.time.Instant;
import java.util.List;

/**
 * @param status    for assistant replies: SUCCEEDED, FAILED or CAPPED. A failed
 *                  reply is part of the history, and it is what retry retries.
 * @param toolCalls for assistant replies: what the agent changed.
 * @param images    for user messages: images sent with it (metadata; bytes at .../attachments/{id})
 * @param review    for assistant replies: the latest visual review of the result, if one was made
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
        Instant createdAt,
        List<AttachmentInfo> images,
        VisualReviewResponse review) {
}
