package com.forgeflow.intelligence.dto;

import java.util.List;

/**
 * What the agent did beyond writing files - the agentic parts of a run, for
 * the UI to show and the evals to measure.
 *
 * @param model      the model that answered (after any fallback)
 * @param tier       FAST or STRONG - what routing chose for this run
 * @param fellBack   true when the chosen model failed and the fallback answered
 * @param plan       the agent's step plan as it last stood (empty if it made none)
 * @param review     the reviewer agent's verdict, or null when no review ran
 * @param guards     guardrail notices: injection-like text found, a secret refused, a budget reached
 * @param question   a clarifying question the agent stopped to ask, or null
 * @param options    suggested answers to that question (may be empty)
 * @param approvals  ids of actions waiting for the user's approval (file deletions)
 * @param remembered facts the agent saved to project memory in this run
 * @param searches   what agentic retrieval did: queries tried and results kept, one line each
 * @param trajectory every tool call in order - how an eval checks the agent's behaviour, not just its output
 */
public record AgentReport(
        String model,
        String tier,
        boolean fellBack,
        List<PlanStep> plan,
        Review review,
        List<String> guards,
        String question,
        List<String> options,
        List<Long> approvals,
        List<String> remembered,
        List<String> searches,
        List<String> trajectory) {

    /** @param status pending, in_progress, done or skipped */
    public record PlanStep(String title, String status) {
    }

    /**
     * @param verdict approve or changes
     * @param rounds  how many times the reviewer looked (a fix round means 2)
     */
    public record Review(String verdict, int rounds, String summary, List<Issue> issues) {
    }

    /** @param severity major (sent back to the builder) or minor (reported only) */
    public record Issue(String severity, String file, String problem) {
    }
}
