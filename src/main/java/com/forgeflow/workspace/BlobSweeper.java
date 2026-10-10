package com.forgeflow.workspace;

import com.forgeflow.shared.storage.ObjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Garbage collection for the bucket: mark and sweep.
 *
 * File contents are content-addressed (projects/{id}/blobs/{sha256}) and
 * never deleted on write - two files with the same content share one object,
 * and a checkpoint may still need the old bytes after the file moves on. So
 * nothing knows, at the moment of a write, whether the previous object is
 * garbage. This does, once a day:
 *
 *   mark    every object_key a row still points at: project_files (what the
 *           project is now) and file_blobs (what any checkpoint can restore)
 *   sweep   every object under projects/ that no row marks AND is older than
 *           the grace period - which covers a write in flight, where the
 *           object exists a moment before the row that will point at it
 *
 * One sweeper at a time across every API replica: a Postgres advisory lock,
 * taken with "try" - a replica that doesn't get it simply skips this round.
 */
@Component
public class BlobSweeper {

    private static final Logger log = LoggerFactory.getLogger(BlobSweeper.class);
    /** Any fixed number; it names the lock. */
    static final long LOCK = 0x46_46_42_4C_4F_42L;      // "FFBLOB"

    public record Report(int listed, int referenced, int deleted, int tooNew, boolean ran) {
    }

    private final ObjectStore store;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Duration grace;

    public BlobSweeper(ObjectProvider<ObjectStore> store, JdbcTemplate jdbc, TransactionTemplate tx,
                       @Value("${forgeflow.storage.sweep.grace-hours:24}") long graceHours) {
        this.store = store.getIfAvailable();
        this.jdbc = jdbc;
        this.tx = tx;
        this.grace = Duration.ofHours(graceHours);
    }

    @Scheduled(cron = "${forgeflow.storage.sweep.cron:0 30 3 * * *}")
    public void nightly() {
        if (store != null) {
            Report r = sweep(grace);
            log.info("blob sweep: {}", r);
        }
    }

    public Report sweep(Duration graceForInFlightWrites) {
        if (store == null) {
            return new Report(0, 0, 0, 0, false);
        }
        Report r = tx.execute(status -> {
            Boolean mine = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, LOCK);
            if (!Boolean.TRUE.equals(mine)) {
                return new Report(0, 0, 0, 0, false);       // another replica is sweeping
            }
            // List BEFORE marking: an object written after the listing isn't
            // considered at all, and one written before it is either marked
            // already or still inside the grace period.
            List<ObjectStore.Stored> objects = store.list("projects/");
            Set<String> marked = new HashSet<>(jdbc.queryForList("""
                    SELECT object_key FROM project_files WHERE object_key IS NOT NULL
                    UNION
                    SELECT object_key FROM file_blobs WHERE object_key IS NOT NULL
                    """, String.class));
            Instant cutoff = Instant.now().minus(graceForInFlightWrites);
            int deleted = 0;
            int tooNew = 0;
            int referenced = 0;
            for (ObjectStore.Stored o : objects) {
                if (marked.contains(o.key())) {
                    referenced++;
                } else if (o.lastModified().isAfter(cutoff)) {
                    tooNew++;
                } else {
                    store.delete(o.key());
                    deleted++;
                }
            }
            return new Report(objects.size(), referenced, deleted, tooNew, true);
        });
        return r;
    }
}
