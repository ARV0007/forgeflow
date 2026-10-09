package com.forgeflow.billing;

/**
 * What a usage_logs row counts. Plain constants, separate from the UsageLog
 * entity, so other modules can name a kind without importing billing's
 * persistence class - the module-boundary test forbids that.
 */
public final class UsageKind {

    public static final String AI_TOKENS = "AI_TOKENS";
    public static final String PROJECT_CREATED = "PROJECT_CREATED";
    public static final String PREVIEW_STARTED = "PREVIEW_STARTED";

    private UsageKind() {
    }
}
