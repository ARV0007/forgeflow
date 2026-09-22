package com.forgeflow.execution;

/**
 * @param output combined stdout/stderr, capped. On failure this is written so
 *               the agent can read it and fix the problem next round.
 */
public record BuildResult(boolean passed, int exitCode, String output, long durationMs, boolean timedOut) {
}
