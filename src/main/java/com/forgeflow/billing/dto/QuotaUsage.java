package com.forgeflow.billing.dto;

/** "3 of 3 projects". {@code limit} is -1 when the plan has none. */
public record QuotaUsage(String quota, long used, long limit) {
}
