package com.forgeflow.execution;

import com.forgeflow.shared.events.CodeGenerated;
import com.forgeflow.shared.events.EventBus;
import com.forgeflow.shared.events.Topics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Second consumer of code.generated (group "execution"): when a run changes
 * files while someone has the preview open, say so in that preview's logs.
 * The preview serves files straight from the project, so a reload shows the
 * new code - the log line is what tells the person watching.
 *
 * Two consumer groups on one topic is the diagram's fan-out: the indexer and
 * the execution service each get every event, and neither knows about the
 * other. On Kafka they can run in different processes; in-process they're
 * two subscribers.
 *
 * Idempotent enough: a duplicate delivery prints the line twice.
 */
@Component
public class CodeChangeNotifier {

    public static final String GROUP = "execution";

    private final ExecutionService execution;
    private final PreviewLogs logs;

    /**
     * @param enabled false on an instance that serves no previews (the
     *                indexing worker): with Kafka it would otherwise take a
     *                share of the "execution" group's partitions and print
     *                notices into logs nobody reads.
     */
    public CodeChangeNotifier(EventBus events, ExecutionService execution, PreviewLogs logs,
                              @Value("${forgeflow.execution.notify-on-code-change:true}") boolean enabled) {
        this.execution = execution;
        this.logs = logs;
        if (enabled) {
            events.subscribe(Topics.CODE_GENERATED, GROUP, CodeGenerated.class, this::on);
        }
    }

    void on(CodeGenerated event) {
        if (event.paths() == null || event.paths().isEmpty() || execution.current(event.projectId()).isEmpty()) {
            return;
        }
        String files = event.paths().size() <= 3
                ? String.join(", ", event.paths())
                : String.join(", ", event.paths().subList(0, 3)) + " +" + (event.paths().size() - 3) + " more";
        // A container or pod serves a copy of the files: redeploy it (the
        // diagram's "code.generated -> execution-service -> pods"). The
        // in-process preview reads the project live and needs nothing.
        boolean redeployed = execution.refreshPreview(event.projectId());
        logs.info(event.projectId(), "preview", "Run " + event.runId() + " changed " + files
                + (redeployed ? " - preview redeployed with the new files" : " - reload the preview to see it"));
    }
}
