package com.forgeflow.billing.dto;

/** Where to send the browser to pay. */
public record CheckoutResponse(String sessionId, String url) {
}
