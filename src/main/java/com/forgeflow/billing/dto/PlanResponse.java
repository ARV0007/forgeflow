package com.forgeflow.billing.dto;

/** A plan as the pricing page shows it. {@code maxTokensPerDay} is -1 when unlimited. */
public record PlanResponse(String code, String name, int priceCents, String currency,
                           int maxProjects, long maxTokensPerDay, int maxPreviews,
                           Object features) {
}
