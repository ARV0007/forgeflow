package com.forgeflow.billing;

import com.forgeflow.account.UserDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Turns verified Stripe events into subscription changes.
 *
 * Everything happens in one transaction, including remembering the event id.
 * If applying the event fails, the id is rolled back with it, our endpoint
 * answers 500, and Stripe retries later - which is exactly what we want. If
 * the id were saved first and the work failed, the retry would be dropped as
 * a duplicate and the customer would have paid for nothing.
 */
@Service
public class StripeWebhooks {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhooks.class);

    private final BillingService billing;
    private final UserDirectory users;
    private final JdbcTemplate jdbc;

    public StripeWebhooks(BillingService billing, UserDirectory users, JdbcTemplate jdbc) {
        this.billing = billing;
        this.users = users;
        this.jdbc = jdbc;
    }

    public enum Outcome { APPLIED, DUPLICATE, IGNORED }

    @Transactional
    public Outcome process(JsonNode event) {
        String id = event.path("id").asText("");
        String type = event.path("type").asText("");
        if (id.isBlank() || type.isBlank()) {
            throw new IllegalArgumentException("Not a Stripe event: no id or type");
        }

        int inserted = jdbc.update(
                "INSERT INTO stripe_events (event_id, type) VALUES (?, ?) ON CONFLICT (event_id) DO NOTHING", id, type);
        if (inserted == 0) {
            log.info("stripe event {} ({}) already processed", id, type);
            return Outcome.DUPLICATE;
        }

        JsonNode object = event.path("data").path("object");
        // Every real Stripe event has "created". Without one, the event simply
        // takes no part in ordering - inventing "now" would make it look newer
        // than events that genuinely came after it.
        Instant createdAt = event.path("created").isNumber()
                ? Instant.ofEpochSecond(event.path("created").asLong())
                : null;
        switch (type) {
            case "checkout.session.completed" -> checkoutCompleted(object, createdAt);
            case "customer.subscription.created", "customer.subscription.updated",
                 "customer.subscription.deleted" -> subscriptionChanged(object, type, createdAt);
            default -> {
                log.debug("stripe event {} ({}) not handled", id, type);
                return Outcome.IGNORED;
            }
        }
        log.info("stripe event {} ({}) applied", id, type);
        return Outcome.APPLIED;
    }

    private void checkoutCompleted(JsonNode session, Instant eventAt) {
        if (!"subscription".equals(session.path("mode").asText())) {
            return;
        }
        Long userId = parseId(session.path("client_reference_id").asText(null));
        if (userId == null) {
            userId = parseId(session.path("metadata").path("user_id").asText(null));
        }
        String subscriptionId = session.path("subscription").asText(null);
        if (userId == null || subscriptionId == null) {
            log.warn("checkout.session.completed without a user or subscription - ignored");
            return;
        }
        users.linkStripeCustomer(userId, session.path("customer").asText(null));

        // "complete" means the first payment went through (or needs no payment
        // yet). Whatever Stripe says next arrives as subscription.updated.
        String paymentStatus = session.path("payment_status").asText("paid");
        String status = "unpaid".equals(paymentStatus) ? Subscription.INCOMPLETE : Subscription.ACTIVE;
        billing.applySubscription("stripe", subscriptionId, userId,
                session.path("metadata").path("plan").asText(Plan.PRO), status, null, null, eventAt);
    }

    private void subscriptionChanged(JsonNode sub, String type, Instant eventAt) {
        String subscriptionId = sub.path("id").asText(null);
        if (subscriptionId == null) {
            return;
        }
        String status = "customer.subscription.deleted".equals(type)
                ? Subscription.CANCELED
                : mapStatus(sub.path("status").asText(""));
        JsonNode metadata = sub.path("metadata");
        billing.applySubscription("stripe", subscriptionId,
                parseId(metadata.path("user_id").asText(null)),
                metadata.path("plan").asText(null),
                status,
                periodEnd(sub),
                sub.has("cancel_at_period_end") ? sub.path("cancel_at_period_end").asBoolean() : null,
                eventAt);
    }

    /**
     * Stripe has more states than a plan needs. The rule: a customer whose card
     * is being retried keeps their plan (PAST_DUE is live); anything final
     * takes it away.
     */
    static String mapStatus(String stripeStatus) {
        return switch (stripeStatus) {
            case "active", "trialing" -> Subscription.ACTIVE;
            case "past_due", "unpaid" -> Subscription.PAST_DUE;
            case "incomplete" -> Subscription.INCOMPLETE;
            default -> Subscription.CANCELED;       // canceled, incomplete_expired, paused
        };
    }

    /**
     * Stripe moved current_period_end from the subscription onto its items in
     * the 2025-03-31 API version. Read whichever the account's API version sends.
     */
    static Instant periodEnd(JsonNode sub) {
        JsonNode top = sub.path("current_period_end");
        if (top.isNumber()) {
            return Instant.ofEpochSecond(top.asLong());
        }
        JsonNode item = sub.path("items").path("data").path(0).path("current_period_end");
        return item.isNumber() ? Instant.ofEpochSecond(item.asLong()) : null;
    }

    private static Long parseId(String s) {
        try {
            return s == null || s.isBlank() ? null : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
