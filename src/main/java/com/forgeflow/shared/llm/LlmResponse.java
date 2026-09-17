package com.forgeflow.shared.llm;

import java.util.List;

/**
 * @param text       assistant prose, when the model is done calling tools
 * @param toolCalls  what the model wants run; empty means the turn is finished
 * @param rawParts   the model turn verbatim, to echo back next round
 */
public record LlmResponse(String text,
                          List<ToolCall> toolCalls,
                          String rawParts,
                          int promptTokens,
                          int completionTokens,
                          int totalTokens) {

    public boolean wantsTools() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
