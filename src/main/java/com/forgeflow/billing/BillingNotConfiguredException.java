package com.forgeflow.billing;

/** Billing is switched off on this deployment. Mapped to 503. */
public class BillingNotConfiguredException extends RuntimeException {

    public BillingNotConfiguredException(String message) {
        super(message);
    }
}
