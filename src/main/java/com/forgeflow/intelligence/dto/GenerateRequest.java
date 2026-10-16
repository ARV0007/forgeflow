package com.forgeflow.intelligence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param codeContext optional: auto (default), always or off - retrieved code for this run
 * @param notes       optional: how many reference notes to attach (0-5); absent = the configured default
 */
public record GenerateRequest(
        @NotBlank @Size(max = 4000) String prompt,
        String codeContext,
        Integer notes) {

    public GenerateRequest(String prompt) {
        this(prompt, null, null);
    }
}
