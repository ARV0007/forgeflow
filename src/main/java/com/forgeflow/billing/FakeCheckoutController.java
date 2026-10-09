package com.forgeflow.billing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The fake provider's stand-in for Stripe's hosted checkout page. Exists only
 * when forgeflow.billing.provider=fake.
 *
 * GET shows a page with a "Pay" button; the POST it submits plays the part of
 * Stripe's webhook, through the same BillingService.applySubscription a real
 * one uses - so local testing exercises the real activation path.
 */
@RestController
@ConditionalOnProperty(name = "forgeflow.billing.provider", havingValue = "fake")
public class FakeCheckoutController {

    private final FakePaymentProvider fake;
    private final BillingService billing;

    public FakeCheckoutController(FakePaymentProvider fake, BillingService billing) {
        this.fake = fake;
        this.billing = billing;
    }

    @GetMapping(value = "/billing/fake-checkout/{sessionId}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(@PathVariable String sessionId) {
        return fake.peek(sessionId)
                .map(p -> ResponseEntity.ok("""
                        <!doctype html><html><head><meta charset="utf-8"><title>Test checkout</title>
                        <style>body{font:16px system-ui;max-width:28rem;margin:4rem auto;padding:0 1rem}
                        .note{background:#fff3cd;padding:.75rem;border-radius:6px}button{font:inherit;padding:.6rem 1.2rem}</style>
                        </head><body><h1>Upgrade to %s</h1>
                        <p class="note">Test mode - no card, no charge. This page stands in for Stripe Checkout.</p>
                        <form method="post"><button type="submit">Complete test payment</button></form>
                        <p><a href="%s">Cancel</a></p></body></html>
                        """.formatted(HtmlUtils.htmlEscape(p.planCode()), HtmlUtils.htmlEscape(p.cancelUrl()))))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("<!doctype html><p>This checkout link has expired or was already used.</p>"));
    }

    @PostMapping("/billing/fake-checkout/{sessionId}")
    public ResponseEntity<Void> pay(@PathVariable String sessionId) {
        FakePaymentProvider.Pending p = fake.take(sessionId).orElse(null);
        if (p == null) {
            return ResponseEntity.notFound().build();
        }
        billing.applySubscription("fake", "sub_fake_" + UUID.randomUUID().toString().replace("-", ""),
                p.userId(), p.planCode(), Subscription.ACTIVE,
                Instant.now().plus(Duration.ofDays(30)), false, Instant.now());
        // 303: "go and GET this", the right answer to a form POST.
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .header(HttpHeaders.LOCATION, URI.create(p.successUrl()).toString())
                .build();
    }
}
