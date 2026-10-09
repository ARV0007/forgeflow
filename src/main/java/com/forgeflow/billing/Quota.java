package com.forgeflow.billing;

/** The three things a plan limits. */
public enum Quota {
    /** Projects owned and not deleted. */
    PROJECTS,
    /** Previews running right now, counted against whoever started them. */
    PREVIEWS,
    /** Model tokens spent since midnight UTC, by whoever asked. */
    AI_TOKENS_PER_DAY
}
