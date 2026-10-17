package com.forgeflow.intelligence.memory;

import com.forgeflow.intelligence.Guardrails;
import com.forgeflow.intelligence.retrieval.CodeIndex;
import com.forgeflow.shared.BadRequestException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Project memory: facts about a project that should outlive one conversation.
 *
 * Chat memory (the last ten messages, plus recall of older ones) is about one
 * conversation. Start a new chat and it's gone. But some things are true of the
 * project, not the chat: "the brand colour is #2f6f4f", "prices are in rupees",
 * "the owner wants it kept to one page". Those are worth writing down once.
 *
 * The agent writes them with a remember tool when the user states something
 * durable; a person can add or delete them in the workbench. Every run starts
 * with them, as a PROJECT MEMORY section - the agent equivalent of a README a
 * new teammate reads first.
 *
 * Small on purpose: 300 characters a fact, 100 facts a project. When they no
 * longer fit the run's budget, the ones that share words with the request come
 * first, then the newest. A credential is never stored, by anyone.
 */
@Component
public class ProjectMemory {

    public static final int MAX_CHARS = 300;
    public static final int MAX_PER_PROJECT = 100;

    public record Fact(Long id, String content, String source, Long createdBy, Long runId, Instant createdAt) {
    }

    /** What remember did: saved as a new fact, or found the same fact already there. */
    public record Saved(Fact fact, boolean alreadyKnown) {
    }

    private final JdbcTemplate jdbc;

    public ProjectMemory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Fact> list(Long projectId) {
        return jdbc.query("SELECT id, content, source, created_by, run_id, created_at FROM project_memories "
                        + "WHERE project_id = ? ORDER BY id",
                (rs, i) -> new Fact(rs.getLong("id"), rs.getString("content"), rs.getString("source"),
                        (Long) rs.getObject("created_by"), (Long) rs.getObject("run_id"),
                        rs.getTimestamp("created_at").toInstant()),
                projectId);
    }

    /**
     * @throws BadRequestException for an empty, too long or credential-bearing fact, or a full memory
     */
    public Saved remember(Long projectId, String content, String source, Long userId, Long runId) {
        String fact = content == null ? "" : content.strip().replaceAll("\\s+", " ");
        if (fact.length() < 3) {
            throw new BadRequestException("A memory needs at least a few words.");
        }
        if (fact.length() > MAX_CHARS) {
            throw new BadRequestException("A memory is at most " + MAX_CHARS + " characters - keep it to one fact.");
        }
        Optional<String> secret = Guardrails.secretIn(fact);
        if (secret.isPresent()) {
            throw new BadRequestException("That looks like a real " + secret.get()
                    + " - credentials are never kept in project memory.");
        }
        Optional<Fact> same = find(projectId, fact);
        if (same.isPresent()) {
            return new Saved(same.get(), true);
        }
        Integer count = jdbc.queryForObject("SELECT count(*) FROM project_memories WHERE project_id = ?",
                Integer.class, projectId);
        if (count != null && count >= MAX_PER_PROJECT) {
            throw new BadRequestException("This project's memory is full (" + MAX_PER_PROJECT
                    + " facts). Delete some that no longer apply.");
        }
        try {
            Long id = jdbc.queryForObject("INSERT INTO project_memories (project_id, content, source, created_by, run_id) "
                    + "VALUES (?, ?, ?, ?, ?) RETURNING id", Long.class, projectId, fact, source, userId, runId);
            return new Saved(get(projectId, id).orElseThrow(), false);
        } catch (DuplicateKeyException e) {
            return new Saved(find(projectId, fact).orElseThrow(), true);       // a concurrent save won
        }
    }

    public boolean forget(Long projectId, Long id) {
        return jdbc.update("DELETE FROM project_memories WHERE project_id = ? AND id = ?", projectId, id) > 0;
    }

    public Optional<Fact> get(Long projectId, Long id) {
        return list(projectId).stream().filter(f -> f.id().equals(id)).findFirst();
    }

    private Optional<Fact> find(Long projectId, String fact) {
        return list(projectId).stream().filter(f -> f.content().equalsIgnoreCase(fact.strip())).findFirst();
    }

    /**
     * The PROJECT MEMORY section for a run, within a character budget; empty
     * when there's nothing to say. Facts that share words with the request
     * first, then the newest - and the section says how many were left out.
     */
    public Optional<String> sectionFor(Long projectId, String request, int maxChars) {
        List<Fact> all = list(projectId);
        if (all.isEmpty()) {
            return Optional.empty();
        }
        List<Fact> ordered = new ArrayList<>(all);
        String keywords = CodeIndex.keywordQuery(request == null ? "" : request);
        List<String> words = keywords == null ? List.of()
                : List.of(keywords.toLowerCase(java.util.Locale.ROOT).split("\\s*\\|\\s*"));
        ordered.sort((a, b) -> {
            int byWords = Long.compare(overlap(b, words), overlap(a, words));
            return byWords != 0 ? byWords : Long.compare(b.id(), a.id());
        });
        StringBuilder sb = new StringBuilder("PROJECT MEMORY - facts saved about this project in earlier work. "
                + "Follow them unless this request says otherwise; if a fact is out of date, forget it and "
                + "remember the new one:\n");
        int shown = 0;
        for (Fact f : ordered) {
            String line = "- [#" + f.id() + "] " + f.content() + "\n";
            if (shown > 0 && sb.length() + line.length() > maxChars) {
                break;
            }
            sb.append(line);
            shown++;
        }
        if (shown < all.size()) {
            sb.append("(").append(all.size() - shown).append(" more not shown)\n");
        }
        return Optional.of(sb.toString().strip());
    }

    private static long overlap(Fact f, List<String> words) {
        String text = f.content().toLowerCase(java.util.Locale.ROOT);
        // Short words ("the", "for") match everything; four letters and up carry meaning.
        return words.stream().filter(w -> w.length() >= 4 && text.contains(w)).count();
    }
}
