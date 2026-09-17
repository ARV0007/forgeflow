package com.forgeflow.intelligence.dto;

import java.util.List;

public record GenerateResponse(
        Long runId,
        String status,
        String stopReason,
        String summary,
        List<String> filesWritten,
        int toolCalls,
        int totalTokens,
        long durationMs) {
}
