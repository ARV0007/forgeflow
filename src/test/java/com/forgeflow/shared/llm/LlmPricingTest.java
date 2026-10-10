package com.forgeflow.shared.llm;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LlmPricingTest {

    @Test
    void cachedPromptTokensAreCheaperAndThinkingIsBilledAsOutput() {
        LlmPricing p = new LlmPricing();
        // 100k prompt tokens of which 40k cached; 6k reply + 4k thinking = total 110k.
        // 60k x 0.25 + 40k x 0.025 + 10k x 1.50, per million = 0.015 + 0.001 + 0.015
        assertThat(p.cost("gemini-3.1-flash-lite", 100_000, 40_000, 110_000)).isEqualByComparingTo("0.031");
    }

    @Test
    void anUnknownModelCostsNothingRatherThanAGuessAndPricesCanBeOverridden() {
        assertThat(new LlmPricing().cost("demo", 5_000, 0, 6_000)).isEqualByComparingTo(BigDecimal.ZERO);
        LlmPricing custom = new LlmPricing("my-model", "1", "", "2");
        assertThat(custom.knows("my-model")).isTrue();
        assertThat(custom.cost("my-model", 1_000_000, 0, 1_500_000)).isEqualByComparingTo("2.0");   // 1 + 0.5 x 2
    }
}
