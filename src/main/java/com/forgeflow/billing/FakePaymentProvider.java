package com.forgeflow.billing;

import com.forgeflow.shared.redis.RespClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A pretend Stripe, for local development and tests. createCheckout returns a
 * link to FakeCheckoutController's page, which "pays" when you press its
 * button - then runs the very same activation code a real Stripe webhook does.
 *
 * Never enable this on a public deployment: anyone could upgrade for free.
 *
 * Pending sessions live in Redis when there is one, so the browser can be
 * sent to the checkout page by one API instance and land on another.
 */
@Component
@ConditionalOnProperty(name = "forgeflow.billing.provider", havingValue = "fake")
public class FakePaymentProvider implements PaymentProvider {

    record Pending(Long userId, String planCode, String successUrl, String cancelUrl) {
    }

    private static final String TAKE = "local v = redis.call('GET', KEYS[1]) redis.call('DEL', KEYS[1]) return v";

    private final Map<String, Pending> sessions = new ConcurrentHashMap<>();
    private final RespClient redis;
    private final ObjectMapper json = new ObjectMapper();

    public FakePaymentProvider(ObjectProvider<RespClient> redis) {
        this.redis = redis.getIfAvailable();
    }

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public Checkout createCheckout(CheckoutRequest request) {
        // Unguessable, like Stripe's: the page behind it needs no login.
        String id = "cs_fake_" + UUID.randomUUID().toString().replace("-", "");
        Pending p = new Pending(request.userId(), request.planCode(), request.successUrl(), request.cancelUrl());
        if (redis != null) {
            call("SET", "ff:fakecheckout:" + id, json.writeValueAsString(p), "EX", "3600");
        } else {
            sessions.put(id, p);
        }
        return new Checkout(id, "/billing/fake-checkout/" + id);
    }

    @Override
    public void cancelAtPeriodEnd(String providerSubscriptionId) {
        // Nothing to tell: there is no provider. BillingService records it locally.
    }

    Optional<Pending> peek(String sessionId) {
        if (redis != null) {
            return parse(call("GET", "ff:fakecheckout:" + sessionId));
        }
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /** One use per session, like a real checkout link. */
    Optional<Pending> take(String sessionId) {
        if (redis != null) {
            return parse(call("EVAL", TAKE, "1", "ff:fakecheckout:" + sessionId));
        }
        return Optional.ofNullable(sessions.remove(sessionId));
    }

    private Optional<Pending> parse(Object raw) {
        return raw == null ? Optional.empty() : Optional.of(json.readValue(String.valueOf(raw), Pending.class));
    }

    private Object call(String... args) {
        try {
            return redis.call(args);
        } catch (IOException e) {
            throw new UncheckedIOException("Redis unreachable for the fake checkout", e);
        }
    }
}
