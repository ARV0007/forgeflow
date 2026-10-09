package com.forgeflow.shared.llm;

import java.util.List;

/**
 * One turn of the conversation, in provider-neutral form.
 *
 * rawModelParts is an opaque provider blob for MODEL turns. Gemini attaches a
 * "thoughtSignature" to each part; if we rebuild the turn from our own fields
 * instead of echoing what we got, the model loses its reasoning between rounds.
 * Every provider needs some version of this, so the field belongs in the
 * abstraction rather than leaking Gemini into the loop.
 */
public record LlmMessage(Role role, String text, String rawModelParts, List<ToolResult> toolResults) {

    public enum Role { USER, MODEL, TOOL_RESULTS }

    public static LlmMessage user(String text) {
        return new LlmMessage(Role.USER, text, null, null);
    }

    public static LlmMessage model(String rawParts) {
        return new LlmMessage(Role.MODEL, null, rawParts, null);
    }

    /**
     * A model turn from an EARLIER run, replayed as plain text for conversation
     * memory. There are no raw parts to echo - that run is over, and its
     * reasoning signatures belong to it - so the provider renders the text.
     */
    public static LlmMessage assistant(String text) {
        return new LlmMessage(Role.MODEL, text, null, null);
    }

    public static LlmMessage toolResults(List<ToolResult> results) {
        return new LlmMessage(Role.TOOL_RESULTS, null, null, results);
    }
}
