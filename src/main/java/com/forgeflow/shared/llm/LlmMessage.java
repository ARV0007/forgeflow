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
public record LlmMessage(Role role, String text, String rawModelParts, List<ToolResult> toolResults,
                         List<ImagePart> images) {

    public enum Role { USER, MODEL, TOOL_RESULTS }

    /** Text-only turns - everything except a user message with pictures. */
    public LlmMessage(Role role, String text, String rawModelParts, List<ToolResult> toolResults) {
        this(role, text, rawModelParts, toolResults, List.of());
    }

    public static LlmMessage user(String text) {
        return new LlmMessage(Role.USER, text, null, null);
    }

    /** A user message with images: a screenshot to copy, a sketch to build from. */
    public static LlmMessage user(String text, List<ImagePart> images) {
        return new LlmMessage(Role.USER, text, null, null, images == null ? List.of() : List.copyOf(images));
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
