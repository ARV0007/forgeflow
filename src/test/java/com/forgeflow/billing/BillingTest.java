package com.forgeflow.billing;

import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.forgeflow.support.ScriptedLlm.CSS;
import static com.forgeflow.support.ScriptedLlm.INDEX;
import static com.forgeflow.support.ScriptedLlm.JS;
import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static com.forgeflow.support.ScriptedLlm.write;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plans, quotas and payment, end to end. The fake provider stands in for
 * Stripe's checkout; Stripe's webhook is exercised for real, with payloads
 * signed the way Stripe signs them.
 */
class BillingTest extends ApiTestSupport {

    private static final String WEBHOOK_SECRET = "whsec_test_only_not_a_real_secret";

    @Autowired
    JdbcTemplate jdbc;

    private Map<String, JsonNode> usageOf(Account a) {
        Map<String, JsonNode> out = new HashMap<>();
        get("/api/v1/billing/me", a, 200).path("usage").forEach(u -> out.put(u.path("quota").asText(), u));
        return out;
    }

    private void buildSite(Account a, long projectId) {
        llm.then(calls(write("index.html", INDEX), write("styles.css", CSS), write("app.js", JS)))
           .then(calls(finish("Built.")));
        post("/api/v1/projects/" + projectId + "/generate", a, Map.of("prompt", "build"), 200);
    }

    // ---------------------------------------------------------------- plans

    @Test
    void thePricingPageIsPublicAndHidesInternalPlans() throws Exception {
        MvcResult res = mvc.perform(MockMvcRequestBuilders.get("/api/v1/billing/plans")).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        JsonNode plans = json.readTree(res.getResponse().getContentAsString());

        assertThat(plans).extracting(p -> p.path("code").asText()).containsExactly("FREE", "PRO");
        assertThat(plans.get(0).path("maxProjects").asInt()).isEqualTo(3);
        assertThat(plans.get(1).path("priceCents").asInt()).isEqualTo(2000);
        assertThat(plans.get(1).path("features").path("highlights")).isNotEmpty();
    }

    @Test
    void aNewUserIsOnFreeWithNothingUsed() {
        Account a = signup("fresh");
        JsonNode me = get("/api/v1/billing/me", a, 200);

        assertThat(me.path("plan").path("code").asText()).isEqualTo("FREE");
        assertThat(me.path("subscriptionStatus").isNull()).isTrue();
        assertThat(me.path("paymentProvider").asText()).isEqualTo("fake");
        Map<String, JsonNode> usage = usageOf(a);
        assertThat(usage.get("PROJECTS").path("used").asLong()).isZero();
        assertThat(usage.get("PROJECTS").path("limit").asLong()).isEqualTo(3);
        assertThat(usage.get("AI_TOKENS_PER_DAY").path("limit").asLong()).isEqualTo(200_000);
    }

    // --------------------------------------------------------------- quotas

    @Test
    void theFourthProjectOnFreeIsA402ThatSaysWhy() {
        Account a = signup("maker");
        long first = createProject(a, "one");
        createProject(a, "two");
        createProject(a, "three");

        JsonNode refused = post("/api/v1/projects", a, Map.of("name", "four", "description", "x"), 402);
        assertThat(refused.path("quota").asText()).isEqualTo("PROJECTS");
        assertThat(refused.path("limit").asLong()).isEqualTo(3);
        assertThat(refused.path("used").asLong()).isEqualTo(3);
        assertThat(refused.path("plan").asText()).isEqualTo("FREE");
        assertThat(refused.path("detail").asText()).contains("3 projects");

        // Deleting one frees the slot.
        call(HttpMethod.DELETE, "/api/v1/projects/" + first, a, null, 204);
        createProject(a, "four");
    }

    @Test
    void projectsSharedWithYouDoNotCountAgainstYourPlan() {
        Account owner = signup("owner");
        Account member = signup("member");
        long shared = createProject(owner, "theirs");
        post("/api/v1/projects/" + shared + "/members", owner, Map.of("email", member.email(), "role", "EDITOR"), 201);

        createProject(member, "a");
        createProject(member, "b");
        createProject(member, "c");    // three of their own, plus one shared: still allowed
        assertThat(usageOf(member).get("PROJECTS").path("used").asLong()).isEqualTo(3);
    }

    @Test
    void freeAllowsOneLivePreviewAndARestartIsNotASecond() {
        Account a = signup("previewer");
        long p1 = createProject(a, "p1");
        long p2 = createProject(a, "p2");
        buildSite(a, p1);
        buildSite(a, p2);

        post("/api/v1/projects/" + p1 + "/preview", a, null, 200);
        post("/api/v1/projects/" + p1 + "/preview", a, null, 200);          // restart: replaces itself
        assertThat(post("/api/v1/projects/" + p2 + "/preview", a, null, 402).path("quota").asText())
                .isEqualTo("PREVIEWS");

        call(HttpMethod.DELETE, "/api/v1/projects/" + p1 + "/preview", a, null, 204);
        post("/api/v1/projects/" + p2 + "/preview", a, null, 200);
    }

    @Test
    void tokensAreMeteredPerRunAndTheDailyLimitStopsTheNextOne() {
        Account a = signup("spender");
        long id = createProject(a, "tokens");
        buildSite(a, id);

        long spent = usageOf(a).get("AI_TOKENS_PER_DAY").path("used").asLong();
        assertThat(spent).isPositive();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM usage_logs WHERE user_id = ? AND kind = 'AI_TOKENS' AND ref LIKE 'run:%'",
                Long.class, a.id())).isEqualTo(1L);

        // Use up the rest of today's allowance.
        jdbc.update("INSERT INTO usage_logs (user_id, kind, quantity, ref) VALUES (?, 'AI_TOKENS', ?, 'test')",
                a.id(), 200_000 - spent);

        JsonNode refused = post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "more"), 402);
        assertThat(refused.path("quota").asText()).isEqualTo("AI_TOKENS_PER_DAY");
        assertThat(refused.path("detail").asText()).contains("midnight UTC");

        // Chat refuses BEFORE saving the question - no orphaned message.
        long session = post("/api/v1/projects/" + id + "/chat/sessions", a, Map.of(), 201).path("id").asLong();
        post("/api/v1/projects/" + id + "/chat/sessions/" + session + "/messages", a, Map.of("content", "hi"), 402);
        assertThat(get("/api/v1/projects/" + id + "/chat/sessions/" + session + "/messages", a, 200)).isEmpty();
    }

    @Test
    void yesterdaysTokensDoNotCountToday() {
        Account a = signup("yesterday");
        long id = createProject(a, "reset");
        jdbc.update("INSERT INTO usage_logs (user_id, kind, quantity, ref, created_at) "
                + "VALUES (?, 'AI_TOKENS', 999999, 'old', now() - interval '2 days')", a.id());

        buildSite(a, id);   // would be a 402 if the old row counted
    }

    // ------------------------------------------------------ fake checkout

    @Test
    void checkingOutWithTheFakeProviderUpgradesToProAndLiftsTheLimit() throws Exception {
        Account a = signup("upgrader");
        createProject(a, "1");
        createProject(a, "2");
        createProject(a, "3");
        post("/api/v1/projects", a, Map.of("name", "4", "description", "x"), 402);

        String url = post("/api/v1/billing/checkout", a, Map.of("plan", "pro"), 200).path("url").asText();
        assertThat(url).startsWith("/billing/fake-checkout/cs_fake_");

        // The checkout page needs no login - like Stripe's, the link is the credential.
        MvcResult page = mvc.perform(MockMvcRequestBuilders.get(url)).andReturn();
        assertThat(page.getResponse().getStatus()).isEqualTo(200);
        assertThat(page.getResponse().getContentAsString()).contains("Test mode");

        MvcResult paid = mvc.perform(MockMvcRequestBuilders.post(url)).andReturn();
        assertThat(paid.getResponse().getStatus()).isEqualTo(303);
        assertThat(paid.getResponse().getHeader(HttpHeaders.LOCATION)).endsWith("/?billing=success");

        // A checkout link works once.
        assertThat(mvc.perform(MockMvcRequestBuilders.post(url)).andReturn().getResponse().getStatus()).isEqualTo(404);

        JsonNode me = get("/api/v1/billing/me", a, 200);
        assertThat(me.path("plan").path("code").asText()).isEqualTo("PRO");
        assertThat(me.path("subscriptionStatus").asText()).isEqualTo("ACTIVE");
        createProject(a, "4");

        // Already on PRO: buying it again is a conflict, not a second charge.
        post("/api/v1/billing/checkout", a, Map.of("plan", "PRO"), 409);
        // FREE and INTERNAL cannot be bought.
        post("/api/v1/billing/checkout", a, Map.of("plan", "INTERNAL"), 404);
    }

    @Test
    void cancellingKeepsTheplanUntilThePeriodEnds() {
        Account a = signup("canceller");
        post("/api/v1/billing/cancel", a, null, 409);          // nothing to cancel on FREE

        String url = post("/api/v1/billing/checkout", a, Map.of("plan", "PRO"), 200).path("url").asText();
        try {
            mvc.perform(MockMvcRequestBuilders.post(url)).andReturn();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        JsonNode after = post("/api/v1/billing/cancel", a, null, 200);
        assertThat(after.path("plan").path("code").asText()).isEqualTo("PRO");
        assertThat(after.path("cancelAtPeriodEnd").asBoolean()).isTrue();
    }

    // ------------------------------------------------------ stripe webhook

    private int webhook(String body, String signatureHeader) throws Exception {
        var req = MockMvcRequestBuilders.post("/api/v1/billing/webhook/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8));
        if (signatureHeader != null) {
            req.header("Stripe-Signature", signatureHeader);
        }
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    private int signedWebhook(String body) throws Exception {
        return webhook(body, StripeSignature.header(body.getBytes(StandardCharsets.UTF_8),
                WEBHOOK_SECRET, Instant.now().getEpochSecond()));
    }

    private static String eventId() {
        return "evt_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String checkoutCompleted(String eventId, Account a, String subId) {
        return """
                {"id":"%s","type":"checkout.session.completed","created":%d,"data":{"object":{
                  "id":"cs_test_1","mode":"subscription","payment_status":"paid",
                  "client_reference_id":"%d","customer":"cus_%s","subscription":"%s",
                  "metadata":{"user_id":"%d","plan":"PRO"}}}}
                """.formatted(eventId, Instant.now().getEpochSecond(), a.id(), subId, subId, a.id());
    }

    private static String subscriptionEvent(String eventId, String type, Account a, String subId,
                                            String status, long periodEnd) {
        return subscriptionEvent(eventId, type, a, subId, status, periodEnd, Instant.now().getEpochSecond());
    }

    private static String subscriptionEvent(String eventId, String type, Account a, String subId,
                                            String status, long periodEnd, long created) {
        // current_period_end on the ITEM, as API versions from 2025-03-31 send it.
        return """
                {"id":"%s","type":"%s","created":%d,"data":{"object":{
                  "id":"%s","status":"%s","cancel_at_period_end":false,
                  "items":{"data":[{"current_period_end":%d}]},
                  "metadata":{"user_id":"%d","plan":"PRO"}}}}
                """.formatted(eventId, type, created, subId, status, periodEnd, a.id());
    }

    @Test
    void aSignedCheckoutEventUpgradesAndAReplayChangesNothing() throws Exception {
        Account a = signup("stripe-buyer");
        String sub = "sub_" + UUID.randomUUID().toString().substring(0, 12);
        String event = checkoutCompleted(eventId(), a, sub);

        assertThat(signedWebhook(event)).isEqualTo(200);
        assertThat(get("/api/v1/billing/me", a, 200).path("plan").path("code").asText()).isEqualTo("PRO");
        assertThat(jdbc.queryForObject("SELECT stripe_customer_id FROM users WHERE id = ?", String.class, a.id()))
                .isEqualTo("cus_" + sub);

        // Stripe delivers at least once. The same event again must be a no-op.
        assertThat(signedWebhook(event)).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscriptions WHERE user_id = ?", Long.class, a.id()))
                .isEqualTo(1L);
    }

    @Test
    void forgedStaleAndUnsignedWebhooksAreRejected() throws Exception {
        Account a = signup("target");
        String event = checkoutCompleted(eventId(), a, "sub_forged");
        byte[] bytes = event.getBytes(StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();

        assertThat(webhook(event, null)).isEqualTo(400);
        assertThat(webhook(event, StripeSignature.header(bytes, "whsec_wrong_secret", now))).isEqualTo(400);
        assertThat(webhook(event, StripeSignature.header(bytes, WEBHOOK_SECRET, now - 3600))).isEqualTo(400);
        // A genuine signature over DIFFERENT bytes: tampering with the body breaks it.
        String tampered = event.replace("\"PRO\"", "\"INTERNAL\"");
        assertThat(webhook(tampered, StripeSignature.header(bytes, WEBHOOK_SECRET, now))).isEqualTo(400);

        assertThat(get("/api/v1/billing/me", a, 200).path("plan").path("code").asText()).isEqualTo("FREE");
    }

    @Test
    void subscriptionEventsMayArriveFirstAndDriveTheWholeLifecycle() throws Exception {
        Account a = signup("lifecycle");
        String sub = "sub_" + UUID.randomUUID().toString().substring(0, 12);
        long periodEnd = Instant.now().plusSeconds(30L * 24 * 3600).getEpochSecond();

        // "created" lands before "checkout completed" - it still has to work.
        assertThat(signedWebhook(subscriptionEvent(eventId(), "customer.subscription.created", a, sub, "active", periodEnd)))
                .isEqualTo(200);
        JsonNode me = get("/api/v1/billing/me", a, 200);
        assertThat(me.path("plan").path("code").asText()).isEqualTo("PRO");
        assertThat(me.path("currentPeriodEnd").asText()).isNotBlank();

        assertThat(signedWebhook(checkoutCompleted(eventId(), a, sub))).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscriptions WHERE user_id = ?", Long.class, a.id()))
                .isEqualTo(1L);

        // A failed renewal: Stripe retries the card, the customer keeps PRO meanwhile.
        signedWebhook(subscriptionEvent(eventId(), "customer.subscription.updated", a, sub, "past_due", periodEnd));
        me = get("/api/v1/billing/me", a, 200);
        assertThat(me.path("plan").path("code").asText()).isEqualTo("PRO");
        assertThat(me.path("subscriptionStatus").asText()).isEqualTo("PAST_DUE");

        // Gone for good.
        signedWebhook(subscriptionEvent(eventId(), "customer.subscription.deleted", a, sub, "canceled", periodEnd));
        assertThat(get("/api/v1/billing/me", a, 200).path("plan").path("code").asText()).isEqualTo("FREE");
    }

    @Test
    void aLateArrivingOlderEventCannotResurrectACancelledPlan() throws Exception {
        Account a = signup("out-of-order");
        String sub = "sub_" + UUID.randomUUID().toString().substring(0, 12);
        long periodEnd = Instant.now().plusSeconds(30L * 24 * 3600).getEpochSecond();
        long t = Instant.now().getEpochSecond();

        signedWebhook(subscriptionEvent(eventId(), "customer.subscription.created", a, sub, "active", periodEnd, t - 60));
        signedWebhook(subscriptionEvent(eventId(), "customer.subscription.deleted", a, sub, "canceled", periodEnd, t));
        // A different event, created between the two, delivered last.
        signedWebhook(subscriptionEvent(eventId(), "customer.subscription.updated", a, sub, "active", periodEnd, t - 30));

        assertThat(get("/api/v1/billing/me", a, 200).path("plan").path("code").asText()).isEqualTo("FREE");
    }

    @Test
    void aLiveSubscriptionLongPastItsPeriodEndNoLongerCounts() throws Exception {
        Account a = signup("missed-webhook");
        String sub = "sub_" + UUID.randomUUID().toString().substring(0, 12);
        long longAgo = Instant.now().minusSeconds(10L * 24 * 3600).getEpochSecond();

        signedWebhook(subscriptionEvent(eventId(), "customer.subscription.updated", a, sub, "active", longAgo));

        // Still "active" in our table - the cancellation webhook was missed - but not honoured.
        assertThat(jdbc.queryForObject("SELECT status FROM subscriptions WHERE provider_subscription_id = ?",
                String.class, sub)).isEqualTo("ACTIVE");
        assertThat(get("/api/v1/billing/me", a, 200).path("plan").path("code").asText()).isEqualTo("FREE");
    }

    @Test
    void unknownEventTypesAreAcknowledgedAndIgnored() throws Exception {
        assertThat(signedWebhook("{\"id\":\"" + eventId() + "\",\"type\":\"invoice.created\",\"data\":{\"object\":{}}}"))
                .isEqualTo(200);
    }

    // ---------------------------------------------------------------- mcp

    @Test
    void theMcpServiceAccountIsOnTheInternalPlan() {
        // Any MCP call provisions the service account.
        post("/mcp", null, Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", "create_project", "arguments", Map.of("name", "via-mcp"))), 200);

        String plan = jdbc.queryForObject("""
                SELECT p.code FROM subscriptions s JOIN plans p ON p.id = s.plan_id
                JOIN users u ON u.id = s.user_id
                WHERE u.provider = 'service' AND s.status = 'ACTIVE' LIMIT 1
                """, String.class);
        assertThat(plan).isEqualTo("INTERNAL");
    }
}
