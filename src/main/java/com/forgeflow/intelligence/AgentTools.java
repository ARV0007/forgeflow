package com.forgeflow.intelligence;

import com.forgeflow.shared.llm.ToolCall;
import com.forgeflow.shared.llm.ToolResult;
import com.forgeflow.shared.llm.ToolSpec;
import com.forgeflow.workspace.ProjectFile;
import com.forgeflow.workspace.ProjectFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

    private final ProjectFileRepository files;

    public AgentTools(ProjectFileRepository files) {
        this.files = files;
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

    @Transactional
    public ToolResult execute(Long projectId, ToolCall call) {
        try {
            return switch (call.name()) {
                case "list_files" -> listFiles(projectId);
                case "read_file" -> readFile(projectId, str(call, "path"));
                case "write_file" -> writeFile(projectId, str(call, "path"), str(call, "content"));
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
        List<ProjectFile> all = files.findByProjectIdOrderByPath(projectId);
        if (all.isEmpty()) {
            return ToolResult.ok("list_files", "(the project is empty)");
        }
        StringBuilder sb = new StringBuilder();
        for (ProjectFile f : all) {
            sb.append(f.getPath()).append("  (").append(f.getSizeBytes()).append(" bytes)\n");
        }
        return ToolResult.ok("list_files", sb.toString());
    }

    private ToolResult readFile(Long projectId, String rawPath) {
        String path = safePath(rawPath);
        return files.findByProjectIdAndPath(projectId, path)
                .map(f -> ToolResult.ok("read_file", f.getContent()))
                .orElseGet(() -> ToolResult.failed("read_file", "No such file: " + path));
    }

    private ToolResult writeFile(Long projectId, String rawPath, String content) {
        String path = safePath(rawPath);
        if (content == null) {
            throw new ToolException("content is required");
        }
        int bytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_FILE_BYTES) {
            throw new ToolException("file too large: " + bytes + " bytes, limit is " + MAX_FILE_BYTES);
        }

        ProjectFile file = files.findByProjectIdAndPath(projectId, path).orElseGet(() -> {
            ProjectFile f = new ProjectFile();
            f.setProjectId(projectId);
            f.setPath(path);
            f.setVersion(0);
            return f;
        });

        file.setContent(content);
        file.setSizeBytes(bytes);
        file.setVersion(file.getVersion() + 1);
        files.save(file);

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
