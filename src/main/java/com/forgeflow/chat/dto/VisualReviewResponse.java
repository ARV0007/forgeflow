package com.forgeflow.chat.dto;

import java.time.Instant;
import java.util.List;

/**
 * A vision model's verdict on how a reply's result looks.
 *
 * @param messageId the assistant reply whose result was reviewed
 * @param score     1-10
 * @param verdict   LOOKS_RIGHT, or NEEDS_FIXES when at least one issue is major
 * @param issues    at most five, each "major" or "minor"
 */
public record VisualReviewResponse(Long id, Long messageId, int score, String verdict, String summary,
                                   List<Issue> issues, int tokensUsed, Instant createdAt) {

    public record Issue(String severity, String text) {
    }
}
