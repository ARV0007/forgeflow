package com.forgeflow.shared.llm;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Reading Gemini's response shape - no network involved. */
class GeminiParseTest {

    private final GeminiClient client = new GeminiClient("unused", "gemini-test", 0.2, 1, 1, 0, 1);

    @Test
    void toolCallsTokensAndCachedTokensAreRead() {
        LlmResponse r = client.parseResponse(new ObjectMapper().readTree("""
                {"candidates":[{"content":{"parts":[
                   {"text":"Writing it."},
                   {"functionCall":{"name":"write_file","args":{"path":"index.html","content":"<h1>x</h1>"}}}]}}],
                 "usageMetadata":{"promptTokenCount":4000,"candidatesTokenCount":120,
                                  "totalTokenCount":4300,"cachedContentTokenCount":3072}}
                """));

        assertThat(r.text()).isEqualTo("Writing it.");
        assertThat(r.toolCalls()).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("write_file");
            assertThat(c.args()).containsEntry("path", "index.html");
        });
        assertThat(r.promptTokens()).isEqualTo(4000);
        assertThat(r.totalTokens()).isEqualTo(4300);     // includes thinking tokens, so not 4000 + 120
        assertThat(r.cachedTokens()).isEqualTo(3072);
    }

    @Test
    void noCacheFieldMeansZero() {
        LlmResponse r = client.parseResponse(new ObjectMapper().readTree("""
                {"candidates":[{"content":{"parts":[{"text":"hi"}]}}],
                 "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":2,"totalTokenCount":12}}
                """));
        assertThat(r.cachedTokens()).isZero();
    }
}
