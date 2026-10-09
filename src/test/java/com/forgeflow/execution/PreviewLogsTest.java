package com.forgeflow.execution;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The log buffer's limits, without Spring. */
class PreviewLogsTest {

    @Test
    void eachProjectKeepsOnlyItsNewestLines() {
        PreviewLogs logs = new PreviewLogs();
        for (int i = 0; i < PreviewLogs.KEEP_PER_PROJECT + 25; i++) {
            logs.info(1L, "build", "line " + i);
        }
        List<PreviewLogs.Line> kept = logs.since(1L, 0);
        assertThat(kept).hasSize(PreviewLogs.KEEP_PER_PROJECT);
        assertThat(kept.get(0).message()).isEqualTo("line 25");
        assertThat(logs.since(2L, 0)).isEmpty();
    }

    @Test
    void consoleLinesFromBrowsersAreRateCapped() {
        PreviewLogs logs = new PreviewLogs();
        int accepted = 0;
        for (int i = 0; i < PreviewLogs.CONSOLE_PER_MINUTE + 50; i++) {
            if (logs.fromConsole(7L, "log", "spam " + i)) {
                accepted++;
            }
        }
        // (Could straddle a minute boundary and accept a few more; never fewer.)
        assertThat(accepted).isBetween(PreviewLogs.CONSOLE_PER_MINUTE, PreviewLogs.CONSOLE_PER_MINUTE + 50);
        assertThat(logs.since(7L, 0)).allMatch(l -> l.level().equals("info"));   // "log" normalises to info
    }

    @Test
    void hugeMessagesAreTruncated() {
        PreviewLogs logs = new PreviewLogs();
        logs.error(3L, "console", "x".repeat(50_000));
        assertThat(logs.since(3L, 0).get(0).message()).hasSizeLessThan(PreviewLogs.MAX_MESSAGE + 50);
    }

    @Test
    void aSubscriberThatThrowsIsDroppedWithoutBreakingOthers() {
        PreviewLogs logs = new PreviewLogs();
        List<String> seen = new ArrayList<>();
        logs.subscribe(4L, l -> { throw new IllegalStateException("tab closed"); });
        logs.subscribe(4L, l -> seen.add(l.message()));

        logs.info(4L, "build", "one");
        logs.info(4L, "build", "two");

        assertThat(seen).containsExactly("one", "two");
        assertThat(logs.subscriberCount(4L)).isEqualTo(1);
    }

    @Test
    void theBridgeGoesStraightAfterTheHeadTag() {
        String out = PreviewContentController.withConsoleBridge(
                "<html><HEAD lang=\"en\"><title>t</title></HEAD></html>", "/p/abc/__log");
        assertThat(out).startsWith("<html><HEAD lang=\"en\"><script>");
        assertThat(out).contains("\"/p/abc/__log\"").endsWith("<title>t</title></HEAD></html>");

        assertThat(PreviewContentController.withConsoleBridge("<p>no head</p>", "/u")).startsWith("<script>");
    }
}
