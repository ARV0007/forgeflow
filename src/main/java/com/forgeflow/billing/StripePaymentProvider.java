package com.forgeflow.billing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Stripe Checkout over plain HTTP - no SDK.
 *
 * Stripe's API takes form-encoded bodies with nested keys written as
 * {@code line_items[0][price]=...}, and answers JSON. That is all an SDK would
 * be doing here; two calls do not justify a dependency (and this build cannot
 * fetch new ones).
 *
 * Note what this class does NOT do: it never marks anyone as paid. Creating a
 * checkout only sends the user to Stripe's page. The subscription is granted
 * when Stripe's signed webhook says payment succeeded - see
 * StripeWebhookController. Trusting the browser's return to success_url would
 * let anyone upgrade by visiting that URL.
 */
@Component
@ConditionalOnProperty(name = "forgeflow.billing.provider", havingValue = "stripe")
public class StripePaymentProvider implements PaymentProvider {

    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final String apiBase;
    private final String secretKey;
    private final String proPriceId;

    public StripePaymentProvider(@Value("${forgeflow.billing.stripe.api-base:https://api.stripe.com}") String apiBase,
                                 @Value("${forgeflow.billing.stripe.secret-key:}") String secretKey,
                                 @Value("${forgeflow.billing.stripe.pro-price-id:}") String proPriceId) {
        if (secretKey.isBlank() || proPriceId.isBlank()) {
            // Fail at startup, not at the first customer's checkout.
            throw new IllegalStateException("forgeflow.billing.provider=stripe needs STRIPE_SECRET_KEY and STRIPE_PRO_PRICE_ID");
        }
        this.apiBase = apiBase.replaceAll("/+$", "");
        this.secretKey = secretKey;
        this.proPriceId = proPriceId;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String name() {
        return "stripe";
    }

    @Override
    public Checkout createCheckout(CheckoutRequest r) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("mode", "subscription");
        form.put("line_items[0][price]", priceFor(r.planCode()));
        form.put("line_items[0][quantity]", "1");
        form.put("success_url", r.successUrl());
        form.put("cancel_url", r.cancelUrl());
        // How the webhook knows whose payment this was.
        form.put("client_reference_id", String.valueOf(r.userId()));
        if (r.existingCustomerId() != null) {
            form.put("customer", r.existingCustomerId());
        } else {
            form.put("customer_email", r.email());
        }
        form.put("metadata[user_id]", String.valueOf(r.userId()));
        form.put("metadata[plan]", r.planCode());
        // Copied onto the subscription itself, so subscription.* events - which
        // can arrive BEFORE checkout.session.completed - still say whose it is.
        form.put("subscription_data[metadata][user_id]", String.valueOf(r.userId()));
        form.put("subscription_data[metadata][plan]", r.planCode());

        JsonNode session = post("/v1/checkout/sessions", form);
        return new Checkout(session.path("id").asText(), session.path("url").asText());
    }

    @Override
    public void cancelAtPeriodEnd(String providerSubscriptionId) {
        post("/v1/subscriptions/" + URLEncoder.encode(providerSubscriptionId, StandardCharsets.UTF_8),
                Map.of("cancel_at_period_end", "true"));
    }

    private String priceFor(String planCode) {
        if (Plan.PRO.equals(planCode)) {
            return proPriceId;
        }
        throw new IllegalArgumentException("No Stripe price configured for plan " + planCode);
    }

    private JsonNode post(String path, Map<String, String> form) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + secretKey)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new PaymentProviderException("Could not reach Stripe: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentProviderException("Interrupted while calling Stripe", e);
        }
        JsonNode body;
        try {
            body = json.readTree(response.body());
        } catch (RuntimeException e) {
            throw new PaymentProviderException("Stripe returned HTTP " + response.statusCode() + " with a non-JSON body");
        }
        if (response.statusCode() / 100 != 2) {
            // Stripe's error message is written for developers and safe to show.
            // The secret key is never in it.
            throw new PaymentProviderException("Stripe refused the request: "
                    + body.path("error").path("message").asText("HTTP " + response.statusCode()));
        }
        return body;
    }

    static String encode(Map<String, String> form) {
        StringJoiner out = new StringJoiner("&");
        form.forEach((k, v) -> out.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8)));
        return out.toString();
    }
}
