package com.forgeflow.billing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A pretend Stripe, for local development and tests. createCheckout returns a
 * link to FakeCheckoutController's page, which "pays" when you press its
 * button - then runs the very same activation code a real Stripe webhook does.
 *
 * Never enable this on a public deployment: anyone could upgrade for free.
 */
@Component
@ConditionalOnProperty(name = "forgeflow.billing.provider", havingValue = "fake")
public class FakePaymentProvider implements PaymentProvider {

    record Pending(Long userId, String planCode, String successUrl, String cancelUrl) {
    }

    private final Map<String, Pending> sessions = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public Checkout createCheckout(CheckoutRequest request) {
        // Unguessable, like Stripe's: the page behind it needs no login.
        String id = "cs_fake_" + UUID.randomUUID().toString().replace("-", "");
        sessions.put(id, new Pending(request.userId(), request.planCode(), request.successUrl(), request.cancelUrl()));
        return new Checkout(id, "/billing/fake-checkout/" + id);
    }

    @Override
    public void cancelAtPeriodEnd(String providerSubscriptionId) {
        // Nothing to tell: there is no provider. BillingService records it locally.
    }

    Optional<Pending> peek(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /** One use per session, like a real checkout link. */
    Optional<Pending> take(String sessionId) {
        return Optional.ofNullable(sessions.remove(sessionId));
    }
}
