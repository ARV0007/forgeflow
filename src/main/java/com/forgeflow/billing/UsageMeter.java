package com.forgeflow.billing;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Writes the usage log, and answers "how many tokens today?" from it.
 *
 * Also the UsageSource for AI tokens - the one quota billing counts itself,
 * because tokens have no table of their own anywhere else.
 */
@Service
public class UsageMeter implements UsageSource {

    private final UsageLogRepository logs;

    public UsageMeter(UsageLogRepository logs) {
        this.logs = logs;
    }

    /**
     * Joins the caller's transaction, deliberately NOT a new one. A usage row
     * for a project created in the same transaction has a foreign key to a row
     * that is not committed yet: a second connection checking that key would
     * wait for the first to commit, while the first waits for the second to
     * return - a deadlock Postgres cannot see, because both halves are us.
     *
     * Token usage is still recorded for failed runs: AgentService records
     * after the run, outside any transaction, whatever the outcome.
     */
    @Transactional
    public void record(Long userId, Long projectId, String kind, long quantity, String ref) {
        if (userId == null || quantity <= 0) {
            return;
        }
        logs.save(new UsageLog(userId, projectId, kind, quantity, ref));
    }

    @Override
    public Quota quota() {
        return Quota.AI_TOKENS_PER_DAY;
    }

    /** Tokens since midnight UTC. One day boundary for everyone - simple to explain, simple to test. */
    @Override
    @Transactional(readOnly = true)
    public long used(Long userId) {
        return logs.sumSince(userId, UsageLog.AI_TOKENS, startOfTodayUtc());
    }

    static Instant startOfTodayUtc() {
        return LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
