package com.forgeflow.chat.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** A screenshot of the live preview, taken in the browser right after the reply landed. */
public record VisualReviewRequest(@NotNull @Valid ImageAttachment screenshot) {
}
