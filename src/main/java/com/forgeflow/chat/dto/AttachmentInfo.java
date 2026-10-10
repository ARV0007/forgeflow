package com.forgeflow.chat.dto;

/** An image stored with a message - fetch the bytes from .../messages/{id}/attachments/{attachmentId}. */
public record AttachmentInfo(Long id, String mimeType, int sizeBytes) {
}
