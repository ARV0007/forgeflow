package com.forgeflow.intelligence;

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

    public static AgentEvent thinking(int round, int promptTokens) {
        return new AgentEvent("thinking", "Round " + round + " (" + promptTokens + " tokens of context)", null, null);
    }

    public static AgentEvent tool(String toolName, String path) {
        return new AgentEvent("tool", toolName, path, null);
    }

    public static AgentEvent file(String path, int bytes) {
        return new AgentEvent("file", "Wrote " + path, path, bytes);
    }

    public static AgentEvent toolFailed(String toolName, String reason) {
        return new AgentEvent("tool_failed", reason, null, toolName);
    }

    public static AgentEvent done(Object result) {
        return new AgentEvent("done", "Finished", null, result);
    }

    public static AgentEvent error(String message) {
        return new AgentEvent("error", message, null, null);
    }
}
