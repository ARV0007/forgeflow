package com.forgeflow.support;

/**
 * Credential-shaped test data for the guardrail tests - none of these are
 * real keys. Each is assembled from pieces, so no source file contains a
 * whole one: secret scanners (GitHub push protection) match the text of a
 * commit, and a complete fake Stripe key once blocked a push.
 */
public final class FakeSecrets {

    private FakeSecrets() {
    }

    public static final String GOOGLE_API_KEY = "AI" + "zaSyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q";
    public static final String STRIPE_SECRET_KEY = "sk_" + "live_" + "51H8abcdefghijklmnopqrstu";
    public static final String GITHUB_TOKEN = "gh" + "p_" + "abcdefghijklmnopqrstuvwxyz0123456789AB";
    public static final String PRIVATE_KEY_HEADER = "-----BEGIN RSA " + "PRIVATE KEY-----";
    public static final String FORGEFLOW_TOKEN = "ff_" + "pat_" + "0123456789abcdefABCDEF0123456789";
}
