package com.forgeflow.intelligence.dto;

import java.util.List;

/**
 * @param status      the OUTCOME - did the code work. SUCCEEDED means the
 *                    build passed, however the loop happened to end.
 * @param stopReason  the MECHANISM - how the loop ended. Kept separate from
 *                    status so a success that ended untidily (NO_TOOL_CALL)
 *                    stays visible to the eval harness.
 */
public record GenerateResponse(
        Long runId,
        String status,
        String stopReason,
        String summary,
        List<String> filesWritten,
        int toolCalls,
        int repairRounds,
        Boolean buildPassed,
        int totalTokens,
        long durationMs) {
}
