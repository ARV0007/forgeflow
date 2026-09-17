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
}
