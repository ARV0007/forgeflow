package com.forgeflow.shared.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * What a model call cost, in US dollars - from the token counts the API
 * reports and a list price per million tokens.
 *
 * Three meters, because Gemini bills them differently:
 *   uncached input   prompt tokens not served from the implicit cache
 *   cached input     prompt tokens it did serve from cache - a tenth of the price
 *   output           everything after the prompt: the reply AND the thinking
 *                    tokens (total - prompt; see GeminiClient on why total is
 *                    not prompt + completion)
 *
 * Prices change, so the configured model's price can be overridden
 * (FORGEFLOW_PRICE_INPUT / _CACHED / _OUTPUT); a model with no known price
 * costs 0 and says so rather than inventing a number.
 */
@Component
public class LlmPricing {

    /** USD per 1M tokens. */
    public record Price(BigDecimal input, BigDecimal cachedInput, BigDecimal output) {
        static Price of(String input, String cached, String output) {
            return new Price(new BigDecimal(input), new BigDecimal(cached), new BigDecimal(output));
        }
    }

    /** Google's paid-tier list prices, ai.google.dev/gemini-api/docs/pricing (checked Oct 2026). */
    static final Map<String, Price> LIST = Map.of(
            "gemini-3.1-flash-lite", Price.of("0.25", "0.025", "1.50"));

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final Map<String, Price> prices = new HashMap<>(LIST);

    public LlmPricing() {
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LlmPricing(@Value("${forgeflow.llm.model:}") String model,
                      @Value("${forgeflow.llm.price.input:}") String input,
                      @Value("${forgeflow.llm.price.cached-input:}") String cached,
                      @Value("${forgeflow.llm.price.output:}") String output) {
        if (!model.isBlank() && !input.isBlank() && !output.isBlank()) {
            prices.put(model, Price.of(input, cached.isBlank() ? input : cached, output));
        }
    }

    public boolean knows(String model) {
        return prices.containsKey(model);
    }

    /** Dollars, to the millionth; zero for a model with no known price (demo, scripted). */
    public BigDecimal cost(String model, int promptTokens, int cachedTokens, int totalTokens) {
        Price p = prices.get(model);
        if (p == null) {
            return BigDecimal.ZERO;
        }
        long cached = Math.max(0, Math.min(cachedTokens, promptTokens));
        long uncached = promptTokens - cached;
        long output = Math.max(0, totalTokens - promptTokens);
        return p.input().multiply(BigDecimal.valueOf(uncached))
                .add(p.cachedInput().multiply(BigDecimal.valueOf(cached)))
                .add(p.output().multiply(BigDecimal.valueOf(output)))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
    }
}
