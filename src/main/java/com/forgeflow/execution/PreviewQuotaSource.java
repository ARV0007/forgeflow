package com.forgeflow.execution;

import com.forgeflow.billing.Quota;
import com.forgeflow.billing.UsageSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Tells billing how many previews someone has running. Expired ones don't count, even before they're swept. */
@Component
class PreviewQuotaSource implements UsageSource {

    private final PreviewRepository previews;

    PreviewQuotaSource(PreviewRepository previews) {
        this.previews = previews;
    }

    @Override
    public Quota quota() {
        return Quota.PREVIEWS;
    }

    @Override
    @Transactional(readOnly = true)
    public long used(Long userId) {
        return previews.countLive(userId, null, Instant.now());
    }
}
