package com.forgeflow.shared.llm;

import java.util.Map;

/** One tool invocation the model asked for. */
public record ToolCall(String name, Map<String, Object> args) {
}
