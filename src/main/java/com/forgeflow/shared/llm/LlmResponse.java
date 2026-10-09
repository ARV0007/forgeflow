package com.forgeflow.shared.llm;

import java.util.List;

/**
 * @param text       assistant prose, when the model is done calling tools
 * @param toolCalls  what the model wants run; empty means the turn is finished
 * @param rawParts   the model turn verbatim, to echo back next round
 * @param cachedTokens how many of the prompt tokens the provider served from
 *                   its prompt cache (billed at a fraction of the price)
 */
public record LlmResponse(String text,
                          List<ToolCall> toolCalls,
                          String rawParts,
                          int promptTokens,
                          int completionTokens,
                          int totalTokens,
                          int cachedTokens) {

    /** For providers (and test doubles) that report no caching. */
    public LlmResponse(String text, List<ToolCall> toolCalls, String rawParts,
                       int promptTokens, int completionTokens, int totalTokens) {
        this(text, toolCalls, rawParts, promptTokens, completionTokens, totalTokens, 0);
    }

    public boolean wantsTools() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
