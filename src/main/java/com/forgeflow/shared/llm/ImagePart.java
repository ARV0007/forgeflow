package com.forgeflow.shared.llm;

import java.util.Base64;
import java.util.Set;

/**
 * An image sent to the model alongside text: a screenshot, a mockup, a sketch
 * on paper. Base64, as every multimodal API wants it on the wire.
 */
public record ImagePart(String mimeType, String base64) {

    public static final Set<String> ALLOWED_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    /** Per image, decoded. Screenshots are well under this; it keeps a request far below Gemini's 20 MB. */
    public static final int MAX_BYTES = 4 * 1024 * 1024;

    /** The raw bytes, or IllegalArgumentException if the type, encoding or size is wrong. */
    public static byte[] validate(String mimeType, String base64) {
        if (mimeType == null || !ALLOWED_TYPES.contains(mimeType)) {
            throw new IllegalArgumentException("Images must be PNG, JPEG or WebP");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64 == null ? "" : base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Image data is not valid base64");
        }
        if (bytes.length == 0) {
            throw new IllegalArgumentException("Image is empty");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("Images can be at most 4 MB (this one is "
                    + (bytes.length / 1024 / 1024) + " MB)");
        }
        // The bytes have to agree with the declared type: a PNG really starts
        // 89 50 4E 47, a JPEG FF D8 FF, a WebP "RIFF....WEBP".
        boolean ok = switch (mimeType) {
            case "image/png" -> bytes.length > 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
            case "image/jpeg" -> bytes.length > 3 && (bytes[0] & 0xff) == 0xFF && (bytes[1] & 0xff) == 0xD8 && (bytes[2] & 0xff) == 0xFF;
            case "image/webp" -> bytes.length > 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            default -> false;
        };
        if (!ok) {
            throw new IllegalArgumentException("The file isn't really a " + mimeType.substring(6).toUpperCase());
        }
        return bytes;
    }
}
