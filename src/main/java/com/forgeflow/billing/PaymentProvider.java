package com.forgeflow.billing;

/**
 * Where money is actually taken. Chosen by forgeflow.billing.provider:
 *
 *   none    (default) billing endpoints answer 503 - nobody can upgrade, and
 *           nobody can upgrade for free by accident either
 *   fake    a stand-in checkout page that "pays" instantly - local dev and tests
 *   stripe  Stripe Checkout over plain HTTP, confirmed by a signed webhook
 *
 * An interface for the same reason SandboxProvider is one: the callers should
 * not change when the backend does.
 */
public interface PaymentProvider {

    /** stripe | fake | none - also the value stored in subscriptions.provider. */
    String name();

    /** Start a hosted checkout and return where to send the user. */
    Checkout createCheckout(CheckoutRequest request);

    /** Stop renewing at the end of the paid period. The webhook confirms it. */
    void cancelAtPeriodEnd(String providerSubscriptionId);

    record CheckoutRequest(Long userId, String email, String planCode, String existingCustomerId,
                           String successUrl, String cancelUrl) {
    }

    record Checkout(String sessionId, String url) {
    }
}
