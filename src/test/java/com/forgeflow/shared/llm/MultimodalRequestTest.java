package com.forgeflow.shared.llm;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Screenshot to app, at the wire: images become inlineData parts after the text. */
class MultimodalRequestTest {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};
    static final String PNG64 = Base64.getEncoder().encodeToString(PNG);

    @Test
    void aUserTurnWithImagesSendsTextThenOneInlineDataPartPerImage() {
        GeminiClient client = new GeminiClient("unused", "gemini-test", 0.2, 1, 1, 0, 1);
        JsonNode req = client.buildRequest("system", List.of(
                LlmMessage.user("Build this", List.of(new ImagePart("image/png", PNG64), new ImagePart("image/jpeg", "AAAA"))),
                LlmMessage.user("plain")), List.of());

        JsonNode parts = req.path("contents").get(0).path("parts");
        assertThat(parts).hasSize(3);
        assertThat(parts.get(0).path("text").asText()).isEqualTo("Build this");
        assertThat(parts.get(1).path("inlineData").path("mimeType").asText()).isEqualTo("image/png");
        assertThat(parts.get(1).path("inlineData").path("data").asText()).isEqualTo(PNG64);
        assertThat(parts.get(2).path("inlineData").path("mimeType").asText()).isEqualTo("image/jpeg");
        assertThat(req.path("contents").get(1).path("parts")).hasSize(1);       // text-only turns unchanged
    }

    @Test
    void onlyRealImagesOfAllowedTypesAndSizesAreAccepted() {
        assertThat(ImagePart.validate("image/png", PNG64)).isEqualTo(PNG);
        assertThatThrownBy(() -> ImagePart.validate("image/gif", PNG64)).hasMessageContaining("PNG, JPEG or WebP");
        assertThatThrownBy(() -> ImagePart.validate("image/jpeg", PNG64)).hasMessageContaining("isn't really a JPEG");
        assertThatThrownBy(() -> ImagePart.validate("image/png", "not base64!")).hasMessageContaining("base64");
        String big = Base64.getEncoder().encodeToString(new byte[ImagePart.MAX_BYTES + 1]);
        assertThatThrownBy(() -> ImagePart.validate("image/png", big)).hasMessageContaining("at most 4 MB");
    }
}
