package com.forgeflow.billing;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Verifies a Stripe-Signature header - the thing that makes a webhook
 * trustworthy. Without it, anyone who finds the URL could POST
 * "checkout.session.completed" and give themselves a paid plan.
 *
 * The header looks like {@code t=1492774577,v1=5257a8...,v1=...}. Stripe signs
 * the string {@code "<t>.<raw body>"} with HMAC-SHA256 using the endpoint's
 * signing secret, and there may be several v1 values while a secret is being
 * rolled - any one matching is enough.
 *
 * Done by hand rather than with Stripe's SDK: it is twenty lines, and it is
 * the twenty lines worth understanding.
 */
public final class StripeSignature {

    /** Stripe's own default. Older than this is treated as a replay. */
    public static final long DEFAULT_TOLERANCE_SECONDS = 300;

    private StripeSignature() {
    }

    public static class InvalidSignatureException extends RuntimeException {
        public InvalidSignatureException(String message) {
            super(message);
        }
    }

    public static void verify(byte[] payload, String header, String secret, long nowEpochSeconds, long toleranceSeconds) {
        if (header == null || header.isBlank()) {
            throw new InvalidSignatureException("Missing Stripe-Signature header");
        }
        Long timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (key.equals("t")) {
                try {
                    timestamp = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    throw new InvalidSignatureException("Malformed timestamp");
                }
            } else if (key.equals("v1")) {
                signatures.add(value);
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            throw new InvalidSignatureException("Stripe-Signature has no timestamp or no v1 signature");
        }
        // Checked on the SIGNED timestamp, so an attacker replaying a captured
        // request cannot simply change it - that would break the signature.
        if (Math.abs(nowEpochSeconds - timestamp) > toleranceSeconds) {
            throw new InvalidSignatureException("Timestamp outside the tolerance window");
        }

        byte[] expected = sign(timestamp + "." + new String(payload, StandardCharsets.UTF_8), secret);
        for (String candidate : signatures) {
            byte[] given;
            try {
                given = HexFormat.of().parseHex(candidate);
            } catch (IllegalArgumentException e) {
                continue;
            }
            // Constant time: a byte-by-byte compare that stops at the first
            // difference leaks, through timing, how much of a guess was right.
            if (MessageDigest.isEqual(expected, given)) {
                return;
            }
        }
        throw new InvalidSignatureException("No signature matches the payload");
    }

    /** HMAC-SHA256 - public so tests can produce a valid header. */
    public static byte[] sign(String signedPayload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static String header(byte[] payload, String secret, long timestamp) {
        String sig = HexFormat.of().formatHex(sign(timestamp + "." + new String(payload, StandardCharsets.UTF_8), secret));
        return "t=" + timestamp + ",v1=" + sig;
    }
}
