package com.forgeflow.execution.dto;

import java.time.Instant;
import java.util.List;

/**
 * A project's published site.
 *
 * @param url          relative - "/s/{slug}/"; the page makes it absolute
 * @param live         false after unpublishing (the slug is kept for next time)
 * @param checkpointId the version being served
 * @param releases     every publish, newest first
 */
public record SiteResponse(String slug, String url, boolean live, long checkpointId, String label,
                           Instant publishedAt, List<Release> releases) {

    public record Release(long checkpointId, String label, Instant publishedAt) {
    }
}
