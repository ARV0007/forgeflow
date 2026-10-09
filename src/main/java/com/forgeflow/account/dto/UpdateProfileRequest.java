package com.forgeflow.account.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * PATCH semantics: a null field means "leave it alone", so a client can change
 * one thing without echoing everything else back.
 *
 * Email is deliberately absent. Changing it is a security operation that needs
 * proof of the new inbox, not a profile edit.
 */
public record UpdateProfileRequest(
        @Size(max = 120) String name,

        // https only: this URL is rendered as an <img> in other people's
        // browsers (member lists), and a plain-http image on an https page is
        // both mixed content and a tracking pixel over the clear.
        @Size(max = 512)
        @Pattern(regexp = "^https://\\S+$", message = "must be an https:// URL")
        String avatarUrl) {
}
