package com.forgeflow.workspace;

import com.forgeflow.shared.ResourceNotFoundException;
import com.forgeflow.shared.events.CodeGenerated;
import com.forgeflow.shared.events.EventBus;
import com.forgeflow.shared.events.Topics;
import com.forgeflow.shared.storage.SigV4;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Version history: checkpoints of the whole project, a diff between any two,
 * and restore.
 *
 *   BASELINE  the project before the first AI change, or before a restore
 *   RUN       after every AI run that changed files (label: the request)
 *   RESTORE   after a restore - so a restore is itself undoable
 *
 * A checkpoint is a list of (path, SHA-256). Contents live once per
 * fingerprint in file_blobs. A checkpoint identical to the latest one (same
 * tree hash) isn't recorded: "nothing changed" isn't a version.
 */
@Service
public class CheckpointService {

    public record Summary(long id, String kind, String label, Long runId, Long createdBy, Instant createdAt,
                          int fileCount, int added, int removed, int changed) {
    }

    public record FileChange(String path, String status, int additions, int deletions, String diff,
                             boolean truncated) {
    }

    private final JdbcTemplate jdbc;
    private final ProjectFileService files;
    private final EventBus events;

    public CheckpointService(JdbcTemplate jdbc, ProjectFileService files, EventBus events) {
        this.jdbc = jdbc;
        this.files = files;
        this.events = events;
    }

    // ------------------------------------------------------------- record

    /** Snapshot the project now. Empty when it's identical to the latest checkpoint. */
    @Transactional
    public Optional<Summary> record(Long projectId, String kind, String label, Long runId, Long userId) {
        List<ProjectFileService.FileRef> refs = files.refs(projectId);
        String tree = treeHash(refs);
        String latest = jdbc.query("SELECT tree_hash FROM checkpoints WHERE project_id = ? ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null, projectId);
        if (tree.equals(latest)) {
            return Optional.empty();
        }
        for (ProjectFileService.FileRef f : refs) {
            jdbc.update("""
                    INSERT INTO file_blobs (project_id, sha256, content, object_key, size_bytes)
                    VALUES (?, ?, ?, ?, ?) ON CONFLICT (project_id, sha256) DO NOTHING""",
                    projectId, f.sha256(), f.content(), f.objectKey(), f.sizeBytes());
        }
        String cleanLabel = label == null || label.isBlank() ? kind.toLowerCase() : label.strip();
        if (cleanLabel.length() > 300) {
            cleanLabel = cleanLabel.substring(0, 297) + "...";
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO checkpoints (project_id, kind, label, run_id, created_by, file_count, tree_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                Long.class, projectId, kind, cleanLabel, runId, userId, refs.size(), tree);
        for (ProjectFileService.FileRef f : refs) {
            jdbc.update("INSERT INTO checkpoint_files (checkpoint_id, path, sha256, size_bytes) VALUES (?, ?, ?, ?)",
                    id, f.path(), f.sha256(), f.sizeBytes());
        }
        return Optional.of(list(projectId, 2).get(0));
    }

    /** Before the first AI change to a project that already has files: remember where it started. */
    @Transactional
    public void ensureBaseline(Long projectId, Long userId) {
        Integer any = jdbc.queryForObject("SELECT count(*) FROM checkpoints WHERE project_id = ?", Integer.class, projectId);
        if (any == 0 && !files.list(projectId).isEmpty()) {
            record(projectId, "BASELINE", "Before the first AI change", null, userId);
        }
    }

    // --------------------------------------------------------------- read

    /** Newest first, each with what changed since the one before it. */
    @Transactional(readOnly = true)
    public List<Summary> list(Long projectId, int limit) {
        record Row(long id, String kind, String label, Long runId, Long createdBy, Instant createdAt, int fileCount) {
        }
        List<Row> rows = jdbc.query("""
                SELECT id, kind, label, run_id, created_by, created_at, file_count
                FROM checkpoints WHERE project_id = ? ORDER BY id DESC LIMIT ?""",
                (rs, i) -> new Row(rs.getLong("id"), rs.getString("kind"), rs.getString("label"),
                        (Long) rs.getObject("run_id"), (Long) rs.getObject("created_by"),
                        rs.getTimestamp("created_at").toInstant(), rs.getInt("file_count")),
                projectId, limit + 1);
        List<Summary> out = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, rows.size()); i++) {
            Row r = rows.get(i);
            Map<String, String> now = tree(r.id());
            Map<String, String> before = i + 1 < rows.size() ? tree(rows.get(i + 1).id()) : Map.of();
            int added = 0;
            int removed = 0;
            int changed = 0;
            for (Map.Entry<String, String> e : now.entrySet()) {
                String was = before.get(e.getKey());
                if (was == null) {
                    added++;
                } else if (!was.equals(e.getValue())) {
                    changed++;
                }
            }
            for (String p : before.keySet()) {
                if (!now.containsKey(p)) {
                    removed++;
                }
            }
            out.add(new Summary(r.id(), r.kind(), r.label(), r.runId(), r.createdBy(), r.createdAt(), r.fileCount(),
                    added, removed, changed));
        }
        return out;
    }

    /**
     * What changed going from {@code against} to {@code id}. Without
     * {@code against}: from the checkpoint before {@code id} (or from nothing).
     */
    @Transactional(readOnly = true)
    public List<FileChange> diff(Long projectId, long id, Long against) {
        requireOwned(projectId, id);
        Long base = against != null ? against : jdbc.query(
                "SELECT id FROM checkpoints WHERE project_id = ? AND id < ? ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, projectId, id);
        if (base != null) {
            requireOwned(projectId, base);
        }
        Map<String, String> to = tree(id);
        Map<String, String> from = base == null ? Map.of() : tree(base);
        Set<String> paths = new TreeSet<>(from.keySet());
        paths.addAll(to.keySet());

        List<FileChange> out = new ArrayList<>();
        for (String path : paths) {
            String a = from.get(path);
            String b = to.get(path);
            if (a != null && a.equals(b)) {
                continue;
            }
            String status = a == null ? "ADDED" : b == null ? "REMOVED" : "MODIFIED";
            LineDiff.Result d = LineDiff.diff(a == null ? "" : blob(projectId, a), b == null ? "" : blob(projectId, b));
            out.add(new FileChange(path, status, d.additions(), d.deletions(), d.unified(), d.truncated()));
        }
        return out;
    }

    /** One file's content as it was at a checkpoint. */
    @Transactional(readOnly = true)
    public Optional<String> contentAt(Long projectId, long id, String path) {
        requireOwned(projectId, id);
        String sha = tree(id).get(path);
        return sha == null ? Optional.empty() : Optional.of(blob(projectId, sha));
    }

    // ------------------------------------------------------------ restore

    /**
     * Make the project exactly what it was at checkpoint {@code id}: rewrite
     * files that differ, delete files it didn't have. The state being left is
     * checkpointed first (if it isn't already), and the result after - so
     * restoring is undone by restoring again.
     */
    @Transactional
    public Summary restore(Long projectId, long id, Long userId) {
        requireOwned(projectId, id);
        Map<String, String> target = tree(id);
        String targetLabel = jdbc.queryForObject("SELECT label FROM checkpoints WHERE id = ?", String.class, id);

        record(projectId, "BASELINE", "Before restoring to #" + id, null, userId);

        Map<String, String> current = new LinkedHashMap<>();
        for (ProjectFileService.FileRef f : files.refs(projectId)) {
            current.put(f.path(), f.sha256());
        }
        List<String> touched = new ArrayList<>();
        for (Map.Entry<String, String> e : target.entrySet()) {
            if (!e.getValue().equals(current.get(e.getKey()))) {
                files.write(projectId, e.getKey(), blob(projectId, e.getValue()), userId);
                touched.add(e.getKey());
            }
        }
        for (String path : current.keySet()) {
            if (!target.containsKey(path)) {
                files.delete(projectId, path);
                touched.add(path);
            }
        }
        Optional<Summary> made = record(projectId, "RESTORE", "Restored to #" + id + ": " + targetLabel, null, userId);
        if (!touched.isEmpty()) {
            // Search re-indexes and an open preview hears about it - the same
            // event an AI run sends, with no run behind it.
            events.publish(Topics.CODE_GENERATED, String.valueOf(projectId),
                    new CodeGenerated(projectId, null, userId, "RESTORED", List.copyOf(touched), Instant.now()));
        }
        return made.orElseGet(() -> list(projectId, 1).get(0));
    }

    // ------------------------------------------------------------ helpers

    private Map<String, String> tree(long checkpointId) {
        Map<String, String> out = new TreeMap<>();
        jdbc.query("SELECT path, sha256 FROM checkpoint_files WHERE checkpoint_id = ?",
                rs -> {
                    out.put(rs.getString(1), rs.getString(2));
                }, checkpointId);
        return out;
    }

    private String blob(Long projectId, String sha) {
        return jdbc.query("SELECT content, object_key FROM file_blobs WHERE project_id = ? AND sha256 = ?",
                rs -> {
                    if (!rs.next()) {
                        throw new IllegalStateException("blob " + sha + " of project " + projectId + " is missing");
                    }
                    return files.blobText(rs.getString(1), rs.getString(2));
                }, projectId, sha);
    }

    private void requireOwned(Long projectId, long id) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM checkpoints WHERE id = ? AND project_id = ?",
                Integer.class, id, projectId);
        if (n == 0) {
            throw new ResourceNotFoundException("No checkpoint " + id + " in this project");
        }
    }

    static String treeHash(List<ProjectFileService.FileRef> refs) {
        StringBuilder sb = new StringBuilder();
        refs.stream().sorted(Comparator.comparing(ProjectFileService.FileRef::path))
                .forEach(f -> sb.append(f.path()).append(' ').append(f.sha256()).append('\n'));
        return SigV4.sha256Hex(sb.toString());
    }
}
