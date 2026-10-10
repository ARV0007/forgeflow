package com.forgeflow.execution;

import com.forgeflow.execution.dto.SiteResponse;
import com.forgeflow.shared.ResourceNotFoundException;
import com.forgeflow.workspace.CheckpointService;
import com.forgeflow.workspace.ProjectService;
import com.forgeflow.workspace.dto.ProjectResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Publish: a project's public site, served from a checkpoint.
 *
 *   publish        snapshot the project (or take the checkpoint you picked)
 *                  and point the site at it - one UPDATE, so the switch is
 *                  atomic: a visitor gets the old version or the new one,
 *                  never half of each
 *   rollback       publish an older checkpoint; the same single UPDATE
 *   unpublish      live = false; the slug is kept, so publishing again
 *                  brings the same address back
 *
 * Because the site points at a checkpoint and checkpoints never change, the
 * published site is immutable: editing the project changes nothing visitors
 * see until the next publish. (Content-addressed blobs mean a release costs
 * no extra storage - it's a pointer.)
 */
@Service
public class SiteService {

    /** What a request for a published file needs: which project, which version. */
    public record Live(Long projectId, long checkpointId, Set<String> paths) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";   // no l/1/o/0

    private final JdbcTemplate jdbc;
    private final ProjectService projects;
    private final CheckpointService checkpoints;

    public SiteService(JdbcTemplate jdbc, ProjectService projects, CheckpointService checkpoints) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.checkpoints = checkpoints;
    }

    @Transactional
    public SiteResponse publish(Long projectId, Long userId, Long checkpointId) {
        ProjectResponse project = projects.requireWrite(projectId, userId);
        long version = checkpointId != null ? checkpointId : checkpoints.snapshot(projectId, userId, "Published");
        Set<String> paths = checkpoints.paths(projectId, version);   // also: 404 if not this project's
        if (!paths.contains("index.html")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That version has no index.html, so there is nothing to show");
        }
        int updated = jdbc.update("""
                UPDATE sites SET checkpoint_id = ?, live = TRUE, published_by = ?, published_at = now()
                WHERE project_id = ?""", version, userId, projectId);
        if (updated == 0) {
            jdbc.update("INSERT INTO sites (project_id, slug, checkpoint_id, published_by) VALUES (?, ?, ?, ?)",
                    projectId, freeSlug(project.name()), version, userId);
        }
        jdbc.update("INSERT INTO site_releases (project_id, checkpoint_id, published_by) VALUES (?, ?, ?)",
                projectId, version, userId);
        return describe(projectId).orElseThrow();
    }

    @Transactional
    public void unpublish(Long projectId, Long userId) {
        projects.requireWrite(projectId, userId);
        if (jdbc.update("UPDATE sites SET live = FALSE WHERE project_id = ? AND live", projectId) == 0) {
            throw new ResourceNotFoundException("This project isn't published");
        }
    }

    @Transactional(readOnly = true)
    public SiteResponse get(Long projectId, Long userId) {
        projects.getById(projectId, userId);
        return describe(projectId).orElseThrow(() -> new ResourceNotFoundException("This project hasn't been published"));
    }

    /** For the public server: the live version behind a slug, if any. Deleted projects serve nothing. */
    @Transactional(readOnly = true)
    public Optional<Live> live(String slug) {
        return jdbc.query("""
                SELECT s.project_id, s.checkpoint_id FROM sites s JOIN projects p ON p.id = s.project_id
                WHERE s.slug = ? AND s.live AND p.deleted_at IS NULL""",
                rs -> rs.next()
                        ? Optional.of(new Live(rs.getLong(1), rs.getLong(2), checkpoints.paths(rs.getLong(1), rs.getLong(2))))
                        : Optional.empty(), slug);
    }

    @Transactional(readOnly = true)
    public Optional<String> read(Live site, String path) {
        return site.paths().contains(path) ? checkpoints.contentAt(site.projectId(), site.checkpointId(), path) : Optional.empty();
    }

    private Optional<SiteResponse> describe(Long projectId) {
        record Row(String slug, boolean live, long checkpointId, java.time.Instant at) {
        }
        Optional<Row> row = jdbc.query("SELECT slug, live, checkpoint_id, published_at FROM sites WHERE project_id = ?",
                rs -> rs.next() ? Optional.of(new Row(rs.getString(1), rs.getBoolean(2), rs.getLong(3),
                        rs.getTimestamp(4).toInstant())) : Optional.<Row>empty(), projectId);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        Row r = row.get();
        List<SiteResponse.Release> releases = jdbc.query("""
                SELECT checkpoint_id, published_at FROM site_releases WHERE project_id = ? ORDER BY id DESC LIMIT 20""",
                (rs, i) -> new SiteResponse.Release(rs.getLong(1), checkpoints.label(projectId, rs.getLong(1)),
                        rs.getTimestamp(2).toInstant()), projectId);
        return Optional.of(new SiteResponse(r.slug(), "/s/" + r.slug() + "/", r.live(), r.checkpointId(),
                checkpoints.label(projectId, r.checkpointId()), r.at(), releases));
    }

    /** "Habit Tracker!" -> "habit-tracker-x7k2": readable, and not guessable from the name alone. */
    private String freeSlug(String name) {
        String base = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (base.length() > 40) {
            base = base.substring(0, 40).replaceAll("-$", "");
        }
        if (base.isEmpty()) {
            base = "app";
        }
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder(base).append('-');
            for (int i = 0; i < 4; i++) {
                sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            String slug = sb.toString();
            Integer taken = jdbc.queryForObject("SELECT count(*) FROM sites WHERE slug = ?", Integer.class, slug);
            if (taken == 0) {
                return slug;
            }
        }
        throw new IllegalStateException("Could not find a free address for this site");
    }
}
