package com.forgeflow.chat.dto;

import com.forgeflow.intelligence.dto.GenerateResponse;

/**
 * One exchange: what was asked, what came back, and the run that produced it.
 *
 * @param userMessage null on a retry - the question was already asked.
 */
public record ChatTurnResponse(
        ChatMessageResponse userMessage,
        ChatMessageResponse assistantMessage,
        GenerateResponse run) {
}
