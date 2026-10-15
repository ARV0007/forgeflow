package com.forgeflow.intelligence;

/**
 * Older parts of a conversation, retrieved because they look relevant to the
 * new request - long chat memory. Built by the chat module, which owns the
 * messages; handed to the agent as a section of its request.
 */
public record RecalledContext(String text, int messages) {
}
