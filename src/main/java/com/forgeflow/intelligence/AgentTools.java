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

                new ToolSpec("write_file",
                        "Create a file, or completely replace one that exists. Provide the entire file content.",
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

    private ToolResult writeFile(Long projectId, Long userId, String rawPath, String content) {
        String path = safePath(rawPath);
        if (content == null) {
            throw new ToolException("content is required");
        }
        int bytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_FILE_BYTES) {
            throw new ToolException("file too large: " + bytes + " bytes, limit is " + MAX_FILE_BYTES);
        }

        files.write(projectId, path, content, userId);

        return ToolResult.ok("write_file", "Wrote " + path + " (" + bytes + " bytes)");
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
