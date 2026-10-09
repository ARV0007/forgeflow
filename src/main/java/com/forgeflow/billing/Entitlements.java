package com.forgeflow.billing;

import com.forgeflow.shared.QuotaExceededException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The billing module's public face for limits: "what plan is this person on,
 * and is there room for one more?"
 *
 * Every quota check in the application is a call to {@link #requireRoomFor}.
 * The numbers come from the plan; the usage comes from whichever module owns
 * the thing being counted (see {@link UsageSource}).
 */
@Service
public class Entitlements {

    /**
     * A subscription whose paid period ended this long ago, still marked live,
     * means a missed cancellation webhook. Treat it as over rather than give
     * the plan away forever. Generous, because renewals arrive by webhook too
     * and a slow one must not downgrade a paying customer.
     */
    static final Duration STALE_AFTER = Duration.ofDays(3);

    private static final List<String> LIVE = List.of(Subscription.ACTIVE, Subscription.PAST_DUE);

    private final PlanRepository plans;
    private final SubscriptionRepository subscriptions;
    private final Map<Quota, UsageSource> sources = new EnumMap<>(Quota.class);

    public Entitlements(PlanRepository plans, SubscriptionRepository subscriptions, List<UsageSource> usageSources) {
        this.plans = plans;
        this.subscriptions = subscriptions;
        for (UsageSource s : usageSources) {
            if (sources.put(s.quota(), s) != null) {
                throw new IllegalStateException("Two UsageSources for " + s.quota());
            }
        }
    }

    /** The plan in force right now. No live subscription means FREE. */
    @Transactional(readOnly = true)
    public Plan planFor(Long userId) {
        return liveSubscription(userId)
                .flatMap(s -> plans.findById(s.getPlanId()))
                .orElseGet(this::freePlan);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<Subscription> liveSubscription(Long userId) {
        return subscriptions.findFirstByUserIdAndStatusIn(userId, LIVE)
                .filter(s -> s.getCurrentPeriodEnd() == null
                        || s.getCurrentPeriodEnd().plus(STALE_AFTER).isAfter(Instant.now()));
    }

    /** Throws 402 if the user has no room for one more of {@code quota}. */
    public void requireRoomFor(Long userId, Quota quota) {
        requireRoomFor(userId, quota, 0);
    }

    /**
     * @param releasing how many of the counted things this action frees as it
     *                  goes - restarting a preview replaces the running one, so
     *                  it must not count against itself.
     */
    @Transactional(readOnly = true)
    public void requireRoomFor(Long userId, Quota quota, long releasing) {
        Plan plan = planFor(userId);
        long limit = plan.limitFor(quota);
        if (limit < 0) {
            return;                         // unlimited on this plan
        }
        long used = Math.max(0, usage(userId, quota) - releasing);
        if (used >= limit) {
            throw new QuotaExceededException(quota.name(), limit, used, plan.getCode(), message(quota, limit, plan));
        }
    }

    /** Current usage of every quota, for the billing page. */
    @Transactional(readOnly = true)
    public Map<Quota, Long> usage(Long userId) {
        Map<Quota, Long> out = new EnumMap<>(Quota.class);
        for (Quota q : Quota.values()) {
            out.put(q, usage(userId, q));
        }
        return out;
    }

    private long usage(Long userId, Quota quota) {
        UsageSource source = sources.get(quota);
        return source == null ? 0 : source.used(userId);
    }

    Plan freePlan() {
        return plans.findByCode(Plan.FREE)
                .orElseThrow(() -> new IllegalStateException("The FREE plan is missing - has V5 run?"));
    }

    private static String message(Quota quota, long limit, Plan plan) {
        return switch (quota) {
            case PROJECTS -> "The " + plan.getName() + " plan allows " + limit + " project"
                    + (limit == 1 ? "" : "s") + ". Delete one or upgrade to create another.";
            case PREVIEWS -> "The " + plan.getName() + " plan allows " + limit + " live preview"
                    + (limit == 1 ? "" : "s") + " at a time. Stop one or upgrade.";
            case AI_TOKENS_PER_DAY -> "You've used today's " + String.format("%,d", limit)
                    + " AI tokens on the " + plan.getName() + " plan. It resets at midnight UTC, or upgrade for more.";
        };
    }
}
