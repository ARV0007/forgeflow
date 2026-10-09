package com.forgeflow.billing;

import com.forgeflow.account.UserDirectory;
import com.forgeflow.account.dto.UserSummary;
import com.forgeflow.billing.dto.BillingStatusResponse;
import com.forgeflow.billing.dto.CheckoutResponse;
import com.forgeflow.billing.dto.PlanResponse;
import com.forgeflow.billing.dto.QuotaUsage;
import com.forgeflow.shared.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Plans, checkout, cancellation - and the one method that changes what plan
 * someone is on, {@link #applySubscription}, which both the Stripe webhook and
 * the fake checkout go through. One door in means one set of rules.
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    private static final List<String> LIVE = List.of(Subscription.ACTIVE, Subscription.PAST_DUE);

    private final PlanRepository plans;
    private final SubscriptionRepository subscriptions;
    private final Entitlements entitlements;
    private final PaymentProvider payments;
    private final UserDirectory users;
    private final String appBaseUrl;
    private final ObjectMapper json = new ObjectMapper();

    public BillingService(PlanRepository plans, SubscriptionRepository subscriptions, Entitlements entitlements,
                          PaymentProvider payments, UserDirectory users,
                          @Value("${forgeflow.billing.app-base-url:http://localhost:8081}") String appBaseUrl) {
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.entitlements = entitlements;
        this.payments = payments;
        this.users = users;
        this.appBaseUrl = appBaseUrl.replaceAll("/+$", "");
    }

    // ---------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public List<PlanResponse> plans() {
        return plans.findByPurchasableTrueOrderByPriceCents().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public BillingStatusResponse status(Long userId) {
        Plan plan = entitlements.planFor(userId);
        Optional<Subscription> sub = entitlements.liveSubscription(userId);
        Map<Quota, Long> used = entitlements.usage(userId);

        List<QuotaUsage> usage = new ArrayList<>();
        for (Quota q : Quota.values()) {
            usage.add(new QuotaUsage(q.name(), used.getOrDefault(q, 0L), plan.limitFor(q)));
        }
        return new BillingStatusResponse(toResponse(plan),
                sub.map(Subscription::getStatus).orElse(null),
                sub.map(Subscription::getCurrentPeriodEnd).orElse(null),
                sub.map(Subscription::getCancelAtPeriodEnd).orElse(false),
                usage, payments.name());
    }

    // -------------------------------------------------------------- actions

    /** Start paying for a plan. Returns the provider's checkout page. */
    public CheckoutResponse checkout(Long userId, String planCode) {
        Plan plan = plans.findByCode(planCode.trim().toUpperCase())
                .filter(Plan::getPurchasable)
                .filter(p -> !Plan.FREE.equals(p.getCode()))
                .orElseThrow(() -> new ResourceNotFoundException("No such plan to buy: " + planCode));

        if (entitlements.planFor(userId).getCode().equals(plan.getCode())) {
            throw new IllegalStateException("You're already on the " + plan.getName() + " plan");
        }
        UserSummary user = users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        PaymentProvider.Checkout c = payments.createCheckout(new PaymentProvider.CheckoutRequest(
                userId, user.email(), plan.getCode(), users.stripeCustomerId(userId).orElse(null),
                appBaseUrl + "/?billing=success", appBaseUrl + "/?billing=cancelled"));
        return new CheckoutResponse(c.sessionId(), c.url());
    }

    /** Stop renewing. The plan stays until the period the user paid for ends. */
    @Transactional
    public BillingStatusResponse cancel(Long userId) {
        Subscription sub = entitlements.liveSubscription(userId)
                .orElseThrow(() -> new IllegalStateException("You're on the Free plan - there's nothing to cancel"));
        if ("internal".equals(sub.getProvider())) {
            throw new IllegalStateException("Internal plans can't be cancelled");
        }
        if ("stripe".equals(sub.getProvider())) {
            payments.cancelAtPeriodEnd(sub.getProviderSubscriptionId());
        }
        // Recorded now so the page reflects it at once; the webhook confirms.
        sub.setCancelAtPeriodEnd(true);
        subscriptions.save(sub);
        return status(userId);
    }

    // -------------------------------------------------------- state changes

    /**
     * Make the database agree with the payment provider about one subscription.
     * Called for every relevant webhook event, in whatever order they arrive.
     *
     * Webhooks are delivered at least once and in no guaranteed order:
     * "subscription updated" can land before "checkout completed". So this is
     * an upsert keyed on the provider's id, never "insert on checkout, update
     * later", and every call carries the full current state rather than a
     * delta.
     *
     * Order is handled with {@code eventAt}: an event created before the last
     * one applied is stale and changes nothing. Without that, a delayed
     * "subscription active" landing after "subscription deleted" would quietly
     * hand the plan back.
     *
     * @param userId   whose it is, if the event says (from metadata); required to create
     * @param planCode which plan, if the event says; required to create
     * @param eventAt  when the provider created the event
     * @return the subscription, or empty if the event was about one we cannot place
     */
    @Transactional
    public Optional<Subscription> applySubscription(String provider, String providerSubscriptionId,
                                                    Long userId, String planCode, String status,
                                                    Instant currentPeriodEnd, Boolean cancelAtPeriodEnd,
                                                    Instant eventAt) {
        Subscription sub = subscriptions.findByProviderSubscriptionId(providerSubscriptionId).orElse(null);
        if (sub != null && eventAt != null && sub.getProviderEventAt() != null
                && eventAt.isBefore(sub.getProviderEventAt())) {
            log.info("{} subscription {}: event from {} is older than {} - ignored",
                    provider, providerSubscriptionId, eventAt, sub.getProviderEventAt());
            return Optional.of(sub);
        }
        if (sub == null) {
            if (userId == null || planCode == null) {
                log.warn("{} subscription {} has no user/plan metadata - ignored", provider, providerSubscriptionId);
                return Optional.empty();
            }
            Plan plan = plans.findByCode(planCode).orElse(null);
            if (plan == null) {
                log.warn("{} subscription {} names unknown plan {} - ignored", provider, providerSubscriptionId, planCode);
                return Optional.empty();
            }
            sub = new Subscription();
            sub.setUserId(userId);
            sub.setPlanId(plan.getId());
            sub.setProvider(provider);
            sub.setProviderSubscriptionId(providerSubscriptionId);
        } else if (planCode != null) {
            // A plan change between paid tiers arrives the same way.
            Subscription existing = sub;
            plans.findByCode(planCode).ifPresent(p -> existing.setPlanId(p.getId()));
        }

        sub.setStatus(status);
        if (eventAt != null) {
            sub.setProviderEventAt(eventAt);
        }
        if (currentPeriodEnd != null) {
            sub.setCurrentPeriodEnd(currentPeriodEnd);
        }
        if (cancelAtPeriodEnd != null) {
            sub.setCancelAtPeriodEnd(cancelAtPeriodEnd);
        }

        if (sub.isLive()) {
            retireOtherLiveSubscriptions(sub.getUserId(), sub.getId());
        }
        return Optional.of(subscriptions.saveAndFlush(sub));
    }

    /**
     * The MCP service account acts for anyone who calls /mcp, so FREE's three
     * projects would be gone in an afternoon. It gets the INTERNAL plan: more
     * room, still capped - an unauthenticated endpoint must not be able to
     * spend unlimited model tokens.
     */
    @Transactional
    public void ensureInternalPlan(Long userId) {
        if (entitlements.liveSubscription(userId).isPresent()) {
            return;
        }
        Plan internal = plans.findByCode(Plan.INTERNAL)
                .orElseThrow(() -> new IllegalStateException("The INTERNAL plan is missing - has V5 run?"));
        Subscription sub = new Subscription();
        sub.setUserId(userId);
        sub.setPlanId(internal.getId());
        sub.setProvider("internal");
        sub.setProviderSubscriptionId("internal-" + userId);
        sub.setStatus(Subscription.ACTIVE);
        try {
            subscriptions.saveAndFlush(sub);
        } catch (DataIntegrityViolationException e) {
            // Another instance got there first. Same outcome.
            log.debug("internal plan for user {} already granted", userId);
        }
    }

    /**
     * At most one live subscription per user (a partial unique index says so).
     * Retire the others BEFORE saving the new one, and flush: Hibernate runs
     * inserts before updates at flush time, so without the explicit flush the
     * new live row would be inserted while the old one was still live.
     */
    private void retireOtherLiveSubscriptions(Long userId, Long keepId) {
        boolean changed = false;
        for (Subscription other : subscriptions.findByUserIdAndStatusIn(userId, LIVE)) {
            if (!other.getId().equals(keepId)) {
                other.setStatus(Subscription.CANCELED);
                changed = true;
            }
        }
        if (changed) {
            subscriptions.flush();
        }
    }

    // -------------------------------------------------------------- helpers

    private PlanResponse toResponse(Plan p) {
        Object features;
        try {
            features = json.readValue(p.getFeatures(), Map.class);
        } catch (RuntimeException e) {
            features = Map.of();
        }
        return new PlanResponse(p.getCode(), p.getName(), p.getPriceCents(), p.getCurrency(),
                p.getMaxProjects(), p.limitFor(Quota.AI_TOKENS_PER_DAY), p.getMaxPreviews(), features);
    }
}
