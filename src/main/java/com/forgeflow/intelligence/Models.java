package com.forgeflow.intelligence;

import com.forgeflow.shared.llm.LlmClient;
import com.forgeflow.shared.llm.LlmException;
import com.forgeflow.shared.llm.LlmMessage;
import com.forgeflow.shared.llm.LlmPricing;
import com.forgeflow.shared.llm.LlmResponse;
import com.forgeflow.shared.llm.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;

/**
 * Which model answers a call, and what happens when it can't.
 *
 * ROUTING. Not every job needs the same model. "Make the button green" is a
 * two-call edit a fast, cheap model does well; "build me a recipe site from
 * this screenshot" is a whole app, where a stronger model earns its price.
 * route() makes that call once per run, from what the run is - new build or
 * edit, pictures or not, how long the request is - and the run stays on that
 * model. (Switching mid-run would mean echoing one model's reasoning
 * signatures to another, which providers don't promise to accept.)
 *
 * FALLBACK. The provider client already retries a 429 or 503 with backoff. If
 * the call still fails - an outage, or a model the provider has retired (seen
 * on Day 9: a 404 overnight) - the same call is tried once on the fallback
 * model, and the run says so. One retry on a different model, never a loop.
 *
 * With no strong or fallback model configured, every call goes to the one
 * configured model and nothing here changes behaviour.
 */
@Component
public class Models {

    private static final Logger log = LoggerFactory.getLogger(Models.class);

    public enum Tier { FAST, STRONG }

    /** What answered: the response, the model that produced it, and whether it was the fallback. */
    public record Served(LlmResponse response, String model, boolean fellBack, BigDecimal costUsd) {
    }

    private final LlmClient llm;
    private final LlmPricing pricing;
    private final String fast;
    private final String strong;
    private final String fallback;
    private final int bigRequestChars;

    public Models(LlmClient llm, LlmPricing pricing,
                  @Value("${forgeflow.llm.model:}") String fast,
                  @Value("${forgeflow.llm.models.strong:}") String strong,
                  @Value("${forgeflow.llm.models.fallback:}") String fallback,
                  @Value("${forgeflow.llm.models.big-request-chars:600}") int bigRequestChars) {
        this.llm = llm;
        this.pricing = pricing;
        this.fast = fast.isBlank() ? llm.modelName() : fast;
        this.strong = strong.isBlank() ? this.fast : strong;
        this.fallback = fallback.isBlank() ? null : fallback;
        this.bigRequestChars = bigRequestChars;
    }

    /**
     * The tier for a builder run. STRONG for a new app (the project is empty),
     * a request with pictures, or a long, detailed request; FAST for the
     * everyday edit to something that exists.
     */
    public Tier route(boolean projectIsEmpty, boolean hasImages, String prompt) {
        if (projectIsEmpty || hasImages || (prompt != null && prompt.length() >= bigRequestChars)) {
            return Tier.STRONG;
        }
        return Tier.FAST;
    }

    /** The model a tier maps to. In the demo and in tests there is only one, so this is its name. */
    public String modelFor(Tier tier) {
        if (!llm.namesModels()) {
            return llm.modelName();
        }
        return tier == Tier.STRONG ? strong : fast;
    }

    /** True when the two tiers really are different models. */
    public boolean routes() {
        return !strong.equals(fast);
    }

    /**
     * One model call on the tier's model, falling back once.
     *
     * @param onFallback told the failing model and the reason, so a run can show it
     */
    public Served chat(Tier tier, String systemPrompt, List<LlmMessage> history, List<ToolSpec> tools,
                       Consumer<String> onFallback) {
        return chat(modelFor(tier), systemPrompt, history, tools, onFallback);
    }

    /** The same, pinned to a model (a run that already chose one stays on it). */
    public Served chat(String model, String systemPrompt, List<LlmMessage> history, List<ToolSpec> tools,
                       Consumer<String> onFallback) {
        try {
            LlmResponse r = llm.chat(model, systemPrompt, history, tools);
            return new Served(r, model, false, cost(model, r));
        } catch (LlmException e) {
            if (fallback == null || fallback.equals(model) || !llm.namesModels()) {
                throw e;
            }
            log.warn("model {} failed ({}); trying the fallback {}", model, e.getMessage(), fallback);
            if (onFallback != null) {
                onFallback.accept(model + " failed (" + shorten(e.getMessage()) + "), so " + fallback + " answered instead");
            }
            LlmResponse r = llm.chat(fallback, systemPrompt, history, tools);
            return new Served(r, fallback, true, cost(fallback, r));
        }
    }

    public String fallbackModel() {
        return fallback;
    }

    private BigDecimal cost(String model, LlmResponse r) {
        return pricing.cost(model, r.promptTokens(), r.cachedTokens(), r.totalTokens());
    }


    private static String shorten(String s) {
        if (s == null) {
            return "no reason given";
        }
        String one = s.replaceAll("\\s+", " ");
        return one.length() > 120 ? one.substring(0, 117) + "..." : one;
    }
}
