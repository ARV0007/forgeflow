package com.forgeflow.intelligence;

import com.forgeflow.shared.llm.ToolCall;
import com.forgeflow.shared.llm.ToolResult;
import com.forgeflow.shared.llm.ToolSpec;
import com.forgeflow.intelligence.retrieval.CodeIndex;
import com.forgeflow.workspace.ProjectFileService;
import com.forgeflow.workspace.dto.FileEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tools the model is allowed to call, and their implementations.
 *
 * There is deliberately no run_arbitrary_command here. The allowlist IS the
 * security boundary: the model can only do things this class implements.
 */
@Component
public class AgentTools {

    private static final Logger log = LoggerFactory.getLogger(AgentTools.class);
    private static final int MAX_FILE_BYTES = 200_000;

    private final ProjectFileService files;
    private final CodeIndex index;

    public AgentTools(ProjectFileService files, CodeIndex index) {
        this.files = files;
        this.index = index;
    }

    public List<ToolSpec> specs() {
        return List.of(
                new ToolSpec("list_files",
                        "List every file currently in the project, with its size in bytes.",
                        Map.of("type", "object", "properties", Map.of())),

                new ToolSpec("read_file",
                        "Read the full contents of one file. Use this before editing a file you did not just write.",
                        schema(Map.of("path", prop("string", "Relative path, e.g. index.html")),
                               List.of("path"))),

                new ToolSpec("search_code",
                        "Search this project's code by meaning and by keyword. Returns the most relevant excerpts "
                                + "with file names and line numbers. Use it to find where something lives in a "
                                + "larger project, then read_file the file before changing it.",
                        schema(Map.of("query", prop("string",
                                        "What you are looking for, e.g. 'dark mode toggle' or 'renderTodos'")),
                               List.of("query"))),

                new ToolSpec("edit_file",
                        "Change part of an existing file: replace one exact piece of text with another. "
                                + "old_text must appear in the file exactly once - copy it from read_file, "
                                + "including enough surrounding lines to make it unique. Prefer this to "
                                + "write_file for small changes.",
                        schema(new LinkedHashMap<>(Map.of(
                                        "path", prop("string", "Relative path of an existing file"),
                                        "old_text", prop("string", "The exact text to replace, as it appears in the file"),
                                        "new_text", prop("string", "What to put in its place (may be empty to delete)"))),
                               List.of("path", "old_text", "new_text"))),

                new ToolSpec("write_file",
                        "Create a new file, or replace one where most of the content changes. Provide the "
                                + "entire file content. To change part of an existing file, use edit_file instead.",
                        schema(new LinkedHashMap<>(Map.of(
                                        "path", prop("string", "Relative path, e.g. index.html or css/style.css"),
                                        "content", prop("string", "The complete file content"))),
                               List.of("path", "content"))),

                new ToolSpec("finish",
                        "Call this when the task is complete. Summarise what you built in one or two sentences.",
                        schema(Map.of("summary", prop("string", "What you built")),
                               List.of("summary")))
        );
    }

    private Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", properties);
        s.put("required", required);
        return s;
    }

    /**
     * @param userId the person whose request this run serves. Files the model
     *               writes are recorded as written by them - the model is their
     *               tool, not an author of its own.
     *
     * Not @Transactional: each file operation has its own short transaction
     * in ProjectFileService, and search_code makes embedding calls that must
     * not hold a database connection while they wait on the network.
     */
    public ToolResult execute(Long projectId, Long userId, ToolCall call) {
        try {
            return switch (call.name()) {
                case "list_files" -> listFiles(projectId);
                case "read_file" -> readFile(projectId, str(call, "path"));
                case "search_code" -> searchCode(projectId, str(call, "query"));
                case "edit_file" -> editFile(projectId, userId, str(call, "path"),
                        str(call, "old_text"), str(call, "new_text"));
                case "write_file" -> writeFile(projectId, userId, str(call, "path"), str(call, "content"));
                case "finish" -> ToolResult.ok("finish", "Done.");
                default -> ToolResult.failed(call.name(), "Unknown tool: " + call.name());
            };
        } catch (ToolException e) {
            // A rejected tool call is an observation, not a crash. The model
            // reads the reason and corrects itself on the next round.
            log.debug("Tool {} rejected: {}", call.name(), e.getMessage());
            return ToolResult.failed(call.name(), e.getMessage());
        } catch (Exception e) {
            log.warn("Tool {} blew up", call.name(), e);
            return ToolResult.failed(call.name(), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private ToolResult listFiles(Long projectId) {
        List<FileEntry> all = files.list(projectId);
        if (all.isEmpty()) {
            return ToolResult.ok("list_files", "(the project is empty)");
        }
        StringBuilder sb = new StringBuilder();
        for (FileEntry f : all) {
            sb.append(f.path()).append("  (").append(f.sizeBytes()).append(" bytes)\n");
        }
        return ToolResult.ok("list_files", sb.toString());
    }

    private ToolResult readFile(Long projectId, String rawPath) {
        String path = safePath(rawPath);
        return files.read(projectId, path)
                .map(content -> ToolResult.ok("read_file", content))
                .orElseGet(() -> ToolResult.failed("read_file", "No such file: " + path));
    }

    private ToolResult searchCode(Long projectId, String query) {
        if (query == null || query.isBlank()) {
            throw new ToolException("query is required");
        }
        List<CodeIndex.SearchHit> hits = index.search(projectId, query, 6);
        if (hits.isEmpty()) {
            return ToolResult.ok("search_code", "No matches. Use list_files to see what exists.");
        }
        StringBuilder sb = new StringBuilder();
        for (CodeIndex.SearchHit h : hits) {
            sb.append("--- ").append(h.path()).append(" lines ").append(h.startLine()).append('-')
              .append(h.endLine()).append(" ---\n").append(h.content()).append("\n\n");
        }
        return ToolResult.ok("search_code", sb.toString().strip());
    }

    /**
     * A targeted edit instead of a full rewrite. Cheaper (the model sends a
     * few lines, not the whole file), and safer: a full rewrite can silently
     * drop parts of a file the model never meant to touch - or a change the
     * user made by hand.
     *
     * The match must be exact and unique. Fuzzy matching would be friendlier
     * and wrong: "close enough" is how an edit lands in the wrong place.
     */
    private ToolResult editFile(Long projectId, Long userId, String rawPath, String oldText, String newText) {
        String path = safePath(rawPath);
        if (oldText == null || oldText.isEmpty()) {
            throw new ToolException("old_text is required - to create or replace a whole file, use write_file");
        }
        if (newText == null) {
            throw new ToolException("new_text is required (use an empty string to delete)");
        }
        String current = files.read(projectId, path)
                .orElseThrow(() -> new ToolException("No such file: " + path + ". Use write_file to create it."));

        int first = current.indexOf(oldText);
        if (first < 0) {
            throw new ToolException("old_text was not found in " + path + ". It must match the file exactly, "
                    + "including spaces and line breaks - read_file and copy the text you want to change.");
        }
        int count = 0;
        for (int i = first; i >= 0; i = current.indexOf(oldText, i + 1)) {
            count++;
        }
        if (count > 1) {
            throw new ToolException("old_text appears " + count + " times in " + path
                    + ". Include more of the surrounding lines so it matches exactly once.");
        }

        String updated = current.substring(0, first) + newText + current.substring(first + oldText.length());
        int bytes = updated.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_FILE_BYTES) {
            throw new ToolException("file would be too large: " + bytes + " bytes, limit is " + MAX_FILE_BYTES);
        }
        files.write(projectId, path, updated, userId);
        return ToolResult.ok("edit_file", "Edited " + path + " (now " + bytes + " bytes)");
    }

    private ToolResult writeFile(Long projectId, Long userId, String rawPath, String content) {
        String path = safePath(rawPath);
        if (content == null) {
            throw new ToolException("content is required");
        }
        int bytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_FILE_BYTES) {
            throw new ToolException("file too large: " + bytes + " bytes, limit is " + MAX_FILE_BYTES);
        }

        String before = files.read(projectId, path).orElse(null);
        files.write(projectId, path, content, userId);

        String result = "Wrote " + path + " (" + bytes + " bytes)";
        if (before != null && mostlyUnchanged(before, content)) {
            // The write still happens - it isn't wrong, just wasteful. The
            // note is for the rest of this run: the model reads tool results,
            // so this is where a nudge actually lands. Seen live: asked to
            // "make the button green", Gemini resent all of styles.css.
            result += ". Note: most of this file was unchanged. For a small change to an existing "
                    + "file, use edit_file - it sends only the part that changes.";
        }
        return ToolResult.ok("write_file", result);
    }

    /** Small files are cheap to rewrite whatever; below this, no note. */
    static final int REWRITE_NOTE_MIN_LINES = 10;

    /**
     * True when a rewrite kept at least 80% of the old file's non-blank lines
     * word for word - i.e. edit_file would have done. A multiset count, so a
     * line that appears twice must survive twice.
     */
    static boolean mostlyUnchanged(String before, String after) {
        List<String> old = before.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        if (old.size() < REWRITE_NOTE_MIN_LINES) {
            return false;
        }
        Map<String, Integer> available = new HashMap<>();
        after.lines().map(String::strip).filter(l -> !l.isEmpty()).forEach(l -> available.merge(l, 1, Integer::sum));
        int kept = 0;
        for (String line : old) {
            Integer n = available.get(line);
            if (n != null && n > 0) {
                available.put(line, n - 1);
                kept++;
            }
        }
        return kept * 5 >= old.size() * 4;
    }

    /**
     * The path guard. The model writes these strings in response to arbitrary
     * user text, so treat every one as hostile until proven otherwise.
     */
    private String safePath(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ToolException("path is required");
        }
        String p = raw.replace('\\', '/').trim();

        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.contains("..")) {
            throw new ToolException("path may not contain '..'");
        }
        if (p.contains("\0")) {
            throw new ToolException("path may not contain null bytes");
        }
        if (p.length() > 512) {
            throw new ToolException("path is too long");
        }
        if (p.isBlank()) {
            throw new ToolException("path is required");
        }
        return p;
    }

    private String str(ToolCall call, String key) {
        Object v = call.args().get(key);
        return v == null ? null : v.toString();
    }

    static class ToolException extends RuntimeException {
        ToolException(String message) {
            super(message);
        }
    }
}
