package com.forgeflow.intelligence;

import java.util.List;

/**
 * A progress event streamed to the client while a run is in flight.
 *
 * We stream OUR events, not the model's tokens. A tool call cannot be half
 * emitted - the model must produce the whole JSON before it means anything -
 * so the unit of progress in an agent is a completed tool call, not a token.
 */
public record AgentEvent(String type, String message, String path, Object data) {

    public static AgentEvent status(String message) {
        return new AgentEvent("status", message, null, null);
    }

    /** RAG at work: the request went out with these retrieved excerpts attached. */
    public static AgentEvent retrieved(int excerpts, List<String> files) {
        return new AgentEvent("retrieved", "Used " + excerpts + " code excerpt" + (excerpts == 1 ? "" : "s")
                + " from " + files.size() + " file" + (files.size() == 1 ? "" : "s"), null, files);
    }

    public static AgentEvent thinking(int round, int promptTokens) {
        return new AgentEvent("thinking", "Round " + round + " (" + promptTokens + " tokens of context)", null, null);
    }

    public static AgentEvent tool(String toolName, String path) {
        return new AgentEvent("tool", toolName, path, null);
    }

    public static AgentEvent file(String path, int bytes) {
        return new AgentEvent("file", "Wrote " + path, path, bytes);
    }

    public static AgentEvent edited(String path, int bytes) {
        return new AgentEvent("file", "Edited " + path, path, bytes);
    }

    public static AgentEvent toolFailed(String toolName, String reason) {
        return new AgentEvent("tool_failed", reason, null, toolName);
    }

    public static AgentEvent build(boolean passed, String output) {
        return new AgentEvent("build", passed ? "Build passed" : "Build failed", null, output);
    }

    public static AgentEvent repair(int round, int max) {
        return new AgentEvent("repair", "Repair round " + round + " of " + max, null, null);
    }

    public static AgentEvent done(Object result) {
        return new AgentEvent("done", "Finished", null, result);
    }

    public static AgentEvent error(String message) {
        return new AgentEvent("error", message, null, null);
    }
}
