package com.forgeflow.execution;

import com.forgeflow.billing.Entitlements;
import com.forgeflow.billing.Quota;
import com.forgeflow.billing.UsageKind;
import com.forgeflow.billing.UsageMeter;
import com.forgeflow.execution.dto.PreviewResponse;
import com.forgeflow.shared.tracing.Tracer;
import com.forgeflow.workspace.ProjectFileService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;

/**
 * The execution module's public face: build, start / stop / look up a preview.
 *
 * Both the HTTP endpoints and the agent's build gate come through here, which
 * is what lets every build - whoever asked for it - land in the project's log
 * stream. Before this class the agent called the SandboxProvider directly and
 * its builds were invisible to anyone watching.
 *
 * Access checks are the caller's job, same as ProjectFileService.
 */
@Service
public class ExecutionService {

    static final Duration PREVIEW_TTL = Duration.ofMinutes(30);
    private static final int MAX_PROBLEM_LINES = 50;

    private final SandboxProvider sandbox;
    private final ProjectFileService files;
    private final PreviewRepository previews;
    private final PreviewLogs logs;
    private final Entitlements entitlements;
    private final UsageMeter usage;
    private final Tracer tracer;

    public ExecutionService(SandboxProvider sandbox, ProjectFileService files,
                            PreviewRepository previews, PreviewLogs logs,
                            Entitlements entitlements, UsageMeter usage, Tracer tracer) {
        this.sandbox = sandbox;
        this.files = files;
        this.previews = previews;
        this.logs = logs;
        this.entitlements = entitlements;
        this.usage = usage;
        this.tracer = tracer;
    }

    public BuildResult build(Long projectId) {
        Map<String, String> snapshot = files.snapshot(projectId);
        logs.info(projectId, "build", "Build started - " + snapshot.size() + " file(s)");

        BuildResult result = tracer.inSpan("sandbox.build", () -> sandbox.build(projectId, snapshot));

        if (result.passed()) {
            logs.info(projectId, "build", "Build passed in " + result.durationMs() + " ms - " + result.output());
        } else {
            logs.error(projectId, "build", "Build failed in " + result.durationMs() + " ms");
            String[] lines = result.output() == null ? new String[0] : result.output().split("\n");
            for (int i = 0; i < lines.length && i < MAX_PROBLEM_LINES; i++) {
                if (!lines[i].isBlank()) {
                    logs.error(projectId, "build", lines[i]);
                }
            }
            if (lines.length > MAX_PROBLEM_LINES) {
                logs.error(projectId, "build", "... and " + (lines.length - MAX_PROBLEM_LINES) + " more");
            }
        }
        return result;
    }

    /**
     * Errors the generated app threw in a real browser since the last build -
     * i.e. from the code as it is now. Distinct messages, oldest first, at
     * most {@code limit}.
     *
     * The build gate checks that files parse and link up. It cannot see a
     * button that throws when clicked. The preview can, because it is running
     * in someone's browser - and the console bridge reports what it saw.
     */
    public List<String> runtimeErrors(Long projectId, int limit) {
        List<PreviewLogs.Line> lines = logs.since(projectId, 0);
        long lastBuild = 0;
        for (PreviewLogs.Line l : lines) {
            if ("build".equals(l.source()) && l.message().startsWith("Build started")) {
                lastBuild = l.seq();
            }
        }
        Set<String> out = new LinkedHashSet<>();
        for (PreviewLogs.Line l : lines) {
            if (l.seq() > lastBuild && "console".equals(l.source()) && "error".equals(l.level())) {
                out.add(l.message());
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * Starts a fresh preview, replacing any running one. Counts against the
     * plan of whoever starts it - but a restart replaces this project's own
     * preview, so that one is not counted against itself.
     */
    public PreviewResponse startPreview(Long projectId, Long userId) {
        entitlements.requireRoomFor(userId, Quota.PREVIEWS, previews.countLive(userId, projectId, Instant.now()));
        markStopped(projectId);
        PreviewHandle handle;
        try {
            handle = sandbox.startPreview(projectId, files.snapshot(projectId));
        } catch (SandboxException e) {
            logs.error(projectId, "preview", "Preview failed to start: " + e.getMessage());
            throw e;
        }

        Instant now = Instant.now();
        Preview p = new Preview();
        p.setProjectId(projectId);
        p.setContainerId(handle.containerName());
        p.setPreviewUrl(handle.url());
        p.setStatus("RUNNING");
        p.setStartedBy(userId);
        p.setStartedAt(now);
        p.setExpiresAt(now.plus(PREVIEW_TTL));
        previews.save(p);
        usage.record(userId, projectId, UsageKind.PREVIEW_STARTED, 1, "preview:" + p.getId());

        logs.info(projectId, "preview", "Preview started at " + handle.url()
                + " (expires in " + PREVIEW_TTL.toMinutes() + " min)");
        return toResponse(p);
    }

    /**
     * code.generated while a preview is running, on a backend that serves a
     * copy of the files: push the new files in. The address stays the same.
     *
     * @return true if there was a preview to refresh
     */
    public boolean refreshPreview(Long projectId) {
        if (!sandbox.previewsCopyFiles() || current(projectId).isEmpty()) {
            return false;
        }
        try {
            sandbox.refreshPreview(projectId, files.snapshot(projectId));
            return true;
        } catch (SandboxException e) {
            logs.error(projectId, "preview", "Preview refresh failed: " + e.getMessage());
            return false;
        }
    }

    public void stopPreview(Long projectId) {
        sandbox.stopPreview(projectId);
        if (markStopped(projectId) > 0) {
            logs.info(projectId, "preview", "Preview stopped");
        }
    }

    /**
     * Spec: "Get Preview". The running preview, if there is one. A preview past
     * its expiry is closed here, lazily - nothing else needs to sweep for them.
     */
    public Optional<PreviewResponse> current(Long projectId) {
        Optional<Preview> running = previews.findByProjectIdAndStatus(projectId, "RUNNING").stream()
                .max(Comparator.comparing(Preview::getStartedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
        if (running.isEmpty()) {
            return Optional.empty();
        }
        Preview p = running.get();
        if (p.getExpiresAt() != null && p.getExpiresAt().isBefore(Instant.now())) {
            sandbox.stopPreview(projectId);
            p.setStatus("EXPIRED");
            previews.save(p);
            logs.info(projectId, "preview", "Preview expired");
            return Optional.empty();
        }
        return Optional.of(toResponse(p));
    }

    private int markStopped(Long projectId) {
        int n = 0;
        for (Preview running : previews.findByProjectIdAndStatus(projectId, "RUNNING")) {
            running.setStatus("STOPPED");
            previews.save(running);
            n++;
        }
        return n;
    }

    private static PreviewResponse toResponse(Preview p) {
        return new PreviewResponse(p.getPreviewUrl(), p.getStatus(), p.getStartedAt(), p.getExpiresAt());
    }
}
