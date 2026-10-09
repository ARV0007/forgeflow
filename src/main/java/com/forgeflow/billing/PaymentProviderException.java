package com.forgeflow.billing;

/** The payment provider refused or could not be reached. Mapped to 502 - their failure, not the caller's. */
public class PaymentProviderException extends RuntimeException {

    public PaymentProviderException(String message) {
        super(message);
    }

    public PaymentProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
