package com.forgeflow.billing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** The default: no payments. Upgrading is refused with a clear 503 rather than silently granted. */
@Component
@ConditionalOnProperty(name = "forgeflow.billing.provider", havingValue = "none", matchIfMissing = true)
public class NoPaymentProvider implements PaymentProvider {

    @Override
    public String name() {
        return "none";
    }

    @Override
    public Checkout createCheckout(CheckoutRequest request) {
        throw new BillingNotConfiguredException(
                "Payments are not set up on this server. Set forgeflow.billing.provider to stripe (or fake for local testing).");
    }

    @Override
    public void cancelAtPeriodEnd(String providerSubscriptionId) {
        throw new BillingNotConfiguredException("Payments are not set up on this server.");
    }
}
