package com.forgeflow.shared.llm;

/**
 * The outcome we hand back to the model. A FAILED result is not an error -
 * it is an observation. The model reads it and tries something else.
 */
public record ToolResult(String toolName, boolean ok, String output) {

    public static ToolResult ok(String tool, String output) {
        return new ToolResult(tool, true, output);
    }

    public static ToolResult failed(String tool, String reason) {
        return new ToolResult(tool, false, "ERROR: " + reason);
    }
}
