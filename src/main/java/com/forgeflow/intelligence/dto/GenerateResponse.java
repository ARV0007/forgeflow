package com.forgeflow.intelligence.dto;

import java.util.List;
import java.util.Map;

/**
 * @param status      the OUTCOME - did the code work. SUCCEEDED means the
 *                    build passed, however the loop happened to end.
 * @param stopReason  the MECHANISM - how the loop ended. Kept separate from
 *                    status so a success that ended untidily (NO_TOOL_CALL)
 *                    stays visible to the eval harness.
 * @param errorMessage why the run broke, when stopReason is ERROR; otherwise null.
 * @param toolUsage   calls per tool name, e.g. {edit_file=1, finish=1, read_file=1}.
 *                    Lets an eval tell a targeted edit from a whole-file rewrite.
 * @param costUsd     what the run's model calls cost at list price (0 for a model with no known price)
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
        long durationMs,
        String errorMessage,
        Map<String, Integer> toolUsage,
        java.math.BigDecimal costUsd) {
}
