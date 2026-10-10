package com.forgeflow.execution;

import com.forgeflow.shared.redis.RespClient;
import com.forgeflow.support.RedisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Two API instances, one Redis: a line logged on either is seen by viewers on both. */
class SharedPreviewLogsTest {

    private RespClient redis;
    private PreviewLogs a;
    private PreviewLogs b;

    @BeforeEach
    void twoInstances() throws InterruptedException {
        assumeTrue(RedisTestSupport.available(), "needs Redis");
        redis = new RespClient(RedisTestSupport.URL, 4, 2000);
        String prefix = "ff:test:logs:" + UUID.randomUUID() + ":";
        a = new PreviewLogs(redis, prefix);
        b = new PreviewLogs(redis, prefix);
        assertThat(a.awaitShared(5, TimeUnit.SECONDS)).isTrue();
        assertThat(b.awaitShared(5, TimeUnit.SECONDS)).isTrue();
    }

    @AfterEach
    void close() {
        if (a != null) {
            a.close();
            b.close();
            redis.close();
        }
    }

    @Test
    void aViewerOnOneInstanceSeesABuildThatRanOnTheOther() {
        List<PreviewLogs.Line> onA = new CopyOnWriteArrayList<>();
        List<PreviewLogs.Line> onB = new CopyOnWriteArrayList<>();
        a.subscribe(7L, onA::add);
        b.subscribe(7L, onB::add);

        b.info(7L, "build", "Build passed");
        a.fromConsole(7L, "error", "TypeError: x is undefined");
        b.info(8L, "build", "another project");

        long deadline = System.currentTimeMillis() + 5000;
        while ((onA.size() < 2 || onB.size() < 2) && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(onA).extracting(PreviewLogs.Line::message).containsExactly("Build passed", "TypeError: x is undefined");
        assertThat(onB).extracting(PreviewLogs.Line::message).containsExactly("Build passed", "TypeError: x is undefined");
        assertThat(onA.get(1).seq()).isGreaterThan(onA.get(0).seq());          // one sequence for everyone

        // The buffer is shared too: a viewer arriving later, on either instance, replays it.
        assertThat(a.since(7L, 0)).extracting(PreviewLogs.Line::source).containsExactly("build", "console");
        assertThat(b.since(7L, onA.get(0).seq())).extracting(PreviewLogs.Line::level).containsExactly("error");
        assertThat(a.lastSeq()).isEqualTo(b.lastSeq()).isGreaterThanOrEqualTo(onA.get(1).seq());
    }

    @Test
    void theSharedBufferKeepsTheNewest500() {
        for (int i = 0; i < PreviewLogs.KEEP_PER_PROJECT + 20; i++) {
            a.info(9L, "http", "GET /" + i);
        }
        List<PreviewLogs.Line> kept = b.since(9L, 0);
        assertThat(kept).hasSize(PreviewLogs.KEEP_PER_PROJECT);
        assertThat(kept.get(0).message()).isEqualTo("GET /20");
    }
}
