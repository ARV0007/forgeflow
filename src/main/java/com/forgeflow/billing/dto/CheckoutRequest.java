package com.forgeflow.billing.dto;

import jakarta.validation.constraints.NotBlank;

public record CheckoutRequest(@NotBlank String plan) {
}
