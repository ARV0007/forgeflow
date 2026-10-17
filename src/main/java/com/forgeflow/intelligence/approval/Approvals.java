package com.forgeflow.intelligence.approval;

import com.forgeflow.shared.BadRequestException;
import com.forgeflow.shared.ResourceNotFoundException;
import com.forgeflow.shared.events.CodeGenerated;
import com.forgeflow.shared.events.EventBus;
import com.forgeflow.shared.events.Topics;
import com.forgeflow.workspace.CheckpointService;
import com.forgeflow.workspace.ProjectFileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Human in the loop for actions that are hard to undo.
 *
 * Most of what the agent does is cheap to reverse: every run is a version in
 * History, one click puts it back. Deleting files is the exception people
 * care about - "it deleted my file" is the classic agent horror story - so
 * the agent can't do it. It can only ask. A delete_file call files a request;
 * the file stays until a person with edit rights approves it in the
 * workbench, and a rejection leaves it exactly where it was.
 *
 * The approval itself is still undoable: the file's last content is saved as
 * a version before it goes.
 */
@Component
public class Approvals {

    private static final Logger log = LoggerFactory.getLogger(Approvals.class);

    public record Request(Long id, Long runId, String kind, String path, String reason, String status,
                          Long requestedBy, Long decidedBy, Instant createdAt, Instant decidedAt) {
    }

    private final JdbcTemplate jdbc;
    private final ProjectFileService files;
    private final CheckpointService checkpoints;
    private final EventBus events;

    public Approvals(JdbcTemplate jdbc, ProjectFileService files, CheckpointService checkpoints, EventBus events) {
        this.jdbc = jdbc;
        this.files = files;
        this.checkpoints = checkpoints;
        this.events = events;
    }

    /** A pending request to delete a file - or the one already waiting for that file. */
    public Request requestDelete(Long projectId, Long runId, Long userId, String path, String reason) {
        Optional<Request> waiting = pendingFor(projectId, path);
        if (waiting.isPresent()) {
            return waiting.get();
        }
        String why = reason == null ? null : (reason.length() > 300 ? reason.substring(0, 297) + "..." : reason.strip());
        try {
            Long id = jdbc.queryForObject("INSERT INTO agent_approvals (project_id, run_id, kind, path, reason, requested_by) "
                    + "VALUES (?, ?, 'DELETE_FILE', ?, ?, ?) RETURNING id", Long.class, projectId, runId, path, why, userId);
            return get(projectId, id);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return pendingFor(projectId, path).orElseThrow();
        }
    }

    public List<Request> list(Long projectId, boolean pendingOnly) {
        return jdbc.query("SELECT * FROM agent_approvals WHERE project_id = ?"
                        + (pendingOnly ? " AND status = 'PENDING'" : "") + " ORDER BY id DESC LIMIT 100",
                (rs, i) -> new Request(rs.getLong("id"), (Long) rs.getObject("run_id"), rs.getString("kind"),
                        rs.getString("path"), rs.getString("reason"), rs.getString("status"),
                        (Long) rs.getObject("requested_by"), (Long) rs.getObject("decided_by"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant()),
                projectId);
    }

    public Request get(Long projectId, Long id) {
        return list(projectId, false).stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("No such approval request in this project"));
    }

    /**
     * Approve: the file goes (its last content kept as a version first).
     * Decided once - the row is claimed with a conditional update, so two
     * people clicking at the same moment can't both act on it.
     */
    public Request approve(Long projectId, Long id, Long userId) {
        Request r = claim(projectId, id, userId, "APPROVED");
        if (files.read(projectId, r.path()).isPresent()) {
            try {
                checkpoints.ensureBaseline(projectId, userId);
            } catch (RuntimeException e) {
                log.warn("baseline before approved delete failed: {}", e.toString());
            }
            files.delete(projectId, r.path());
            try {
                checkpoints.record(projectId, "RUN", "Deleted " + r.path() + " (approved)", r.runId(), userId);
            } catch (RuntimeException e) {
                log.warn("checkpoint after approved delete failed: {}", e.toString());
            }
            events.publish(Topics.CODE_GENERATED, String.valueOf(projectId), new CodeGenerated(projectId,
                    r.runId(), userId, "APPROVED", List.of(r.path()), Instant.now()));
        }
        return get(projectId, id);
    }

    public Request reject(Long projectId, Long id, Long userId) {
        claim(projectId, id, userId, "REJECTED");
        return get(projectId, id);
    }

    private Request claim(Long projectId, Long id, Long userId, String outcome) {
        Request r = get(projectId, id);
        int n = jdbc.update("UPDATE agent_approvals SET status = ?, decided_by = ?, decided_at = now() "
                + "WHERE id = ? AND project_id = ? AND status = 'PENDING'", outcome, userId, id, projectId);
        if (n == 0) {
            throw new BadRequestException("That request was already " + get(projectId, id).status().toLowerCase() + ".");
        }
        return r;
    }

    private Optional<Request> pendingFor(Long projectId, String path) {
        return list(projectId, true).stream().filter(r -> r.path().equals(path)).findFirst();
    }
}
