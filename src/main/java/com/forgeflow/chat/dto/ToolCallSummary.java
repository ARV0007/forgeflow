package com.forgeflow.chat.dto;

/** One thing the agent did in a reply - enough to render "wrote index.html". */
public record ToolCallSummary(String name, String path) {
}
