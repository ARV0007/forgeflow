package com.forgeflow.shared;

/**
 * A plan limit was reached. Mapped to 402 Payment Required: the request was
 * fine, and paying (upgrading) is exactly what would let it through.
 *
 * Lives in shared, not billing, so the exception handler can map it without
 * shared depending on a feature module.
 */
public class QuotaExceededException extends RuntimeException {

    private final String quota;
    private final long limit;
    private final long used;
    private final String plan;

    public QuotaExceededException(String quota, long limit, long used, String plan, String message) {
        super(message);
        this.quota = quota;
        this.limit = limit;
        this.used = used;
        this.plan = plan;
    }

    public String quota() { return quota; }
    public long limit() { return limit; }
    public long used() { return used; }
    public String plan() { return plan; }
}
