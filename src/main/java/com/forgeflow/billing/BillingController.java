package com.forgeflow.billing;

import com.forgeflow.billing.dto.BillingStatusResponse;
import com.forgeflow.billing.dto.CheckoutRequest;
import com.forgeflow.billing.dto.CheckoutResponse;
import com.forgeflow.billing.dto.PlanResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Spec: Stripe payments, FREE / PRO plans.
 *
 *   GET  /api/v1/billing/plans            public - the pricing page
 *   GET  /api/v1/billing/me               your plan, and how much of it you've used
 *   POST /api/v1/billing/checkout         start paying; returns the checkout URL
 *   POST /api/v1/billing/cancel           stop renewing at period end
 *   POST /api/v1/billing/webhook/stripe   Stripe's signed callbacks (no JWT - the signature IS the auth)
 */
@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private static final Logger log = LoggerFactory.getLogger(BillingController.class);

    private final BillingService billing;
    private final StripeWebhooks webhooks;
    private final String webhookSecret;
    private final ObjectMapper json = new ObjectMapper();

    public BillingController(BillingService billing, StripeWebhooks webhooks,
                             @Value("${forgeflow.billing.stripe.webhook-secret:}") String webhookSecret) {
        this.billing = billing;
        this.webhooks = webhooks;
        this.webhookSecret = webhookSecret;
    }

    @GetMapping("/plans")
    public List<PlanResponse> plans() {
        return billing.plans();
    }

    @GetMapping("/me")
    public BillingStatusResponse me(Authentication auth) {
        return billing.status((Long) auth.getPrincipal());
    }

    @PostMapping("/checkout")
    public CheckoutResponse checkout(@Valid @RequestBody CheckoutRequest req, Authentication auth) {
        return billing.checkout((Long) auth.getPrincipal(), req.plan());
    }

    @PostMapping("/cancel")
    public BillingStatusResponse cancel(Authentication auth) {
        return billing.cancel((Long) auth.getPrincipal());
    }

    /**
     * Takes the body as raw bytes ON PURPOSE. The signature covers the exact
     * bytes Stripe sent; parse to an object and re-serialise, and a single
     * reordered key or changed space would make a genuine event fail.
     */
    @PostMapping("/webhook/stripe")
    public ResponseEntity<Map<String, String>> stripeWebhook(
            @RequestBody byte[] payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        if (webhookSecret.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Stripe webhooks are not configured on this server"));
        }
        try {
            StripeSignature.verify(payload, signature, webhookSecret,
                    Instant.now().getEpochSecond(), StripeSignature.DEFAULT_TOLERANCE_SECONDS);
        } catch (StripeSignature.InvalidSignatureException e) {
            log.warn("rejected a Stripe webhook: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "invalid signature"));
        }

        JsonNode event;
        try {
            event = json.readTree(payload);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "body is not JSON"));
        }
        // Anything thrown past here is a 500, and Stripe will retry - see StripeWebhooks.
        StripeWebhooks.Outcome outcome = webhooks.process(event);
        return ResponseEntity.ok(Map.of("result", outcome.name().toLowerCase()));
    }

    @ExceptionHandler(BillingNotConfiguredException.class)
    ProblemDetail notConfigured(BillingNotConfiguredException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(PaymentProviderException.class)
    ProblemDetail providerFailed(PaymentProviderException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
    }
}
