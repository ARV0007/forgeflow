package com.forgeflow.billing;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Stripe pieces that need no database: the signature check, the status
 * mapping, and the HTTP call itself - made against a stub server on
 * localhost, so the exact request Stripe would receive can be inspected.
 */
class StripeUnitTest {

    private static final String SECRET = "whsec_unit";

    @Test
    void aSignatureMadeWithTheSecretVerifies() {
        byte[] body = "{\"id\":\"evt_1\"}".getBytes(StandardCharsets.UTF_8);
        String header = StripeSignature.header(body, SECRET, 1_700_000_000L);

        StripeSignature.verify(body, header, SECRET, 1_700_000_100L, 300);   // no throw
    }

    @Test
    void anyMatchingV1IsEnoughWhileASecretIsBeingRolled() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String good = StripeSignature.header(body, SECRET, 1_700_000_000L);
        String withOldFirst = "t=1700000000,v1=" + "ab".repeat(32) + "," + good.substring(good.indexOf("v1="));

        StripeSignature.verify(body, withOldFirst, SECRET, 1_700_000_000L, 300);
    }

    @Test
    void badHeadersAreRefused() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        long t = 1_700_000_000L;

        assertThatThrownBy(() -> StripeSignature.verify(body, "", SECRET, t, 300)).hasMessageContaining("Missing");
        assertThatThrownBy(() -> StripeSignature.verify(body, "t=" + t, SECRET, t, 300)).hasMessageContaining("no v1");
        assertThatThrownBy(() -> StripeSignature.verify(body, "t=x,v1=00", SECRET, t, 300)).hasMessageContaining("Malformed");
        assertThatThrownBy(() -> StripeSignature.verify(body, StripeSignature.header(body, SECRET, t), SECRET, t + 301, 300))
                .hasMessageContaining("tolerance");
        assertThatThrownBy(() -> StripeSignature.verify(body, "t=" + t + ",v1=not-hex", SECRET, t, 300))
                .hasMessageContaining("No signature matches");
    }

    @Test
    void stripeStatusesMapToWhetherThePlanIsKept() {
        assertThat(StripeWebhooks.mapStatus("active")).isEqualTo("ACTIVE");
        assertThat(StripeWebhooks.mapStatus("trialing")).isEqualTo("ACTIVE");
        assertThat(StripeWebhooks.mapStatus("past_due")).isEqualTo("PAST_DUE");
        assertThat(StripeWebhooks.mapStatus("unpaid")).isEqualTo("PAST_DUE");
        assertThat(StripeWebhooks.mapStatus("incomplete")).isEqualTo("INCOMPLETE");
        assertThat(StripeWebhooks.mapStatus("incomplete_expired")).isEqualTo("CANCELED");
        assertThat(StripeWebhooks.mapStatus("paused")).isEqualTo("CANCELED");
    }

    @Test
    void checkoutSendsTheFormStripeExpects() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"id\":\"cs_test_42\",\"url\":\"https://checkout.stripe.com/c/pay/cs_test_42\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            StripePaymentProvider stripe = new StripePaymentProvider(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "sk_test_abc", "price_pro_1");

            PaymentProvider.Checkout c = stripe.createCheckout(new PaymentProvider.CheckoutRequest(
                    7L, "a@b.dev", "PRO", null, "https://app/?billing=success", "https://app/?billing=cancelled"));

            assertThat(c.sessionId()).isEqualTo("cs_test_42");
            assertThat(c.url()).startsWith("https://checkout.stripe.com/");
            assertThat(path.get()).isEqualTo("/v1/checkout/sessions");
            assertThat(auth.get()).isEqualTo("Bearer sk_test_abc");

            Map<String, String> form = parse(body.get());
            assertThat(form).containsEntry("mode", "subscription")
                    .containsEntry("line_items[0][price]", "price_pro_1")
                    .containsEntry("line_items[0][quantity]", "1")
                    .containsEntry("client_reference_id", "7")
                    .containsEntry("customer_email", "a@b.dev")
                    .containsEntry("subscription_data[metadata][user_id]", "7")
                    .containsEntry("subscription_data[metadata][plan]", "PRO");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stripesErrorMessageSurfacesAndTheKeyDoesNot() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] out = "{\"error\":{\"message\":\"No such price: 'price_x'\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            StripePaymentProvider stripe = new StripePaymentProvider(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "sk_test_secret_value", "price_x");
            assertThatThrownBy(() -> stripe.createCheckout(new PaymentProvider.CheckoutRequest(
                    1L, "a@b.dev", "PRO", "cus_1", "s", "c")))
                    .isInstanceOf(PaymentProviderException.class)
                    .hasMessageContaining("No such price")
                    .hasMessageNotContaining("sk_test_secret_value");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void periodEndIsReadFromEitherPlaceStripePutsIt() {
        ObjectMapper json = new ObjectMapper();
        assertThat(StripeWebhooks.periodEnd(json.readTree("{\"current_period_end\":1700000000}")).getEpochSecond())
                .isEqualTo(1_700_000_000L);
        assertThat(StripeWebhooks.periodEnd(json.readTree(
                "{\"items\":{\"data\":[{\"current_period_end\":1800000000}]}}")).getEpochSecond())
                .isEqualTo(1_800_000_000L);
        assertThat(StripeWebhooks.periodEnd(json.readTree("{}"))).isNull();
    }

    private static Map<String, String> parse(String form) {
        Map<String, String> out = new HashMap<>();
        for (String pair : form.split("&")) {
            int eq = pair.indexOf('=');
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }
}
