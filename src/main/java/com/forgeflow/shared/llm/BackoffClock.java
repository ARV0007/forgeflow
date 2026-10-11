package com.forgeflow.shared.llm;

/**
 * Time this thread spent waiting out a provider's rate limit.
 *
 * The agent has a time budget per run, and on the free tier a 429 with "retry
 * in 7 s" is routine. Counting that wait against the budget meant a run could
 * time out while doing nothing but obeying the provider. The client records
 * each backoff here; the agent loop drains it after every call and moves its
 * deadline by that much. A ThreadLocal is enough: a run makes its model calls
 * on one thread, one after another.
 */
public final class BackoffClock {

    private static final ThreadLocal<long[]> WAITED = ThreadLocal.withInitial(() -> new long[1]);

    private BackoffClock() {
    }

    static void waited(long millis) {
        WAITED.get()[0] += millis;
    }

    /** Milliseconds waited on this thread since the last drain. */
    public static long drain() {
        long[] w = WAITED.get();
        long ms = w[0];
        w[0] = 0;
        return ms;
    }
}
