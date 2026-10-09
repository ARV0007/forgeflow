package com.forgeflow.workspace;

import com.forgeflow.billing.Quota;
import com.forgeflow.billing.UsageSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Tells billing how many projects someone owns. Shared-with-me projects don't count - you didn't make them. */
@Component
class ProjectQuotaSource implements UsageSource {

    private final ProjectRepository projects;

    ProjectQuotaSource(ProjectRepository projects) {
        this.projects = projects;
    }

    @Override
    public Quota quota() {
        return Quota.PROJECTS;
    }

    @Override
    @Transactional(readOnly = true)
    public long used(Long userId) {
        return projects.countByOwnerIdAndDeletedAtIsNull(userId);
    }
}
