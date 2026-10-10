package com.forgeflow.chat.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * @param images optional - up to three screenshots, mockups or sketches for
 *               the agent to build from (PNG, JPEG or WebP, 4 MB each)
 */
public record SendMessageRequest(@NotBlank @Size(max = 4000) String content,
                                 @Valid @Size(max = 3, message = "at most 3 images per message") List<ImageAttachment> images) {

    public SendMessageRequest(String content) {
        this(content, null);
    }
}
