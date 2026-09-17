package com.forgeflow.shared.llm;

import java.util.Map;

/**
 * A tool the model may call. parameters is a JSON-Schema-shaped map:
 * {"type":"object","properties":{...},"required":[...]}
 */
public record ToolSpec(String name, String description, Map<String, Object> parameters) {
}
