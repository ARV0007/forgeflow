package com.forgeflow.billing.dto;

import java.time.Instant;
import java.util.List;

/**
 * Everything the account page needs: the plan in force, the subscription
 * behind it (null fields on FREE), and how much of each limit is used.
 */
public record BillingStatusResponse(PlanResponse plan,
                                    String subscriptionStatus,
                                    Instant currentPeriodEnd,
                                    boolean cancelAtPeriodEnd,
                                    List<QuotaUsage> usage,
                                    String paymentProvider) {
}
