package com.forgeflow.shared.llm;

import java.util.List;

/**
 * The whole model provider, in one method. Swapping Gemini for Anthropic or
 * OpenAI means one more implementation and a config change - nothing in the
 * agent loop knows which provider it is talking to.
 */
public interface LlmClient {

    LlmResponse chat(String systemPrompt, List<LlmMessage> history, List<ToolSpec> tools);

    String modelName();

    /**
     * The same call on a named model - how routing (a fast model for small
     * edits, a stronger one for new builds) and fallback (a backup model when
     * one is down) reach the provider. A provider with a single model ignores
     * the name.
     */
    /** True when chat(model, ...) really switches models; false for a one-model stand-in (the demo). */
    default boolean namesModels() {
        return false;
    }

    default LlmResponse chat(String model, String systemPrompt, List<LlmMessage> history, List<ToolSpec> tools) {
        return chat(systemPrompt, history, tools);
    }
}
