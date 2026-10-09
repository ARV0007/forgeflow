package com.forgeflow.mcp;

import com.forgeflow.intelligence.AgentService;
import com.forgeflow.workspace.ProjectFileRepository;
import com.forgeflow.workspace.ProjectService;
import com.forgeflow.workspace.dto.CreateProjectRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Runs a named MCP tool against the real services.
 *
 * MCP requests carry no JWT, so everything here acts as one fixed service
 * account. Projects made through MCP belong to that user, not to whoever is
 * driving the client.
 */
@Component
public class McpToolExecutor {

    private final ProjectService projects;
    private final AgentService agent;
    private final ProjectFileRepository files;
    private final Long ownerId;

    public McpToolExecutor(ProjectService projects,
                           AgentService agent,
                           ProjectFileRepository files,
                           @Value("${forgeflow.mcp.owner-id:1}") Long ownerId) {
        this.projects = projects;
        this.agent = agent;
        this.files = files;
        this.ownerId = ownerId;
    }

    /** A tool result: text the model reads, plus whether it went wrong. */
    static Map<String, Object> content(String text, boolean isError) {
        return Map.of(
                "content", List.of(Map.of("type", "text", "text", text)),
                "isError", isError
        );
    }

    public Map<String, Object> call(String name, JsonNode args) {
        try {
            return switch (name) {
                case "create_project" -> createProject(args);
                case "generate_app" -> generateApp(args);
                case "list_project_files" -> listFiles(args);
                default -> content("No such tool: " + name, true);
            };
        } catch (Exception e) {
            // Deliberately a TOOL error, not a JSON-RPC error. The request was
            // well formed; the world was wrong. A bad project_id or a failed
            // build goes back into the calling model's context where it can
            // read it and recover - the same reasoning as the agent's own
            // self-healing loop. A JSON-RPC error would surface to the user as
            // "the server is broken" and the model could not act on it.
            return content(e.getClass().getSimpleName() + ": " + e.getMessage(), true);
        }
    }

    private Map<String, Object> createProject(JsonNode args) {
        String name = args.path("name").asText();
        String desc = args.path("description").asText();
        var p = projects.create(ownerId, new CreateProjectRequest(name, desc));
        return content("Created project " + p.id() + " (\"" + p.name() + "\"). "
                + "Use this project_id with generate_app.", false);
    }

    private Map<String, Object> generateApp(JsonNode args) {
        Long projectId = args.path("project_id").asLong();
        String prompt = args.path("prompt").asText();
        var run = agent.generate(projectId, ownerId, prompt);
        String report = "runId=" + run.runId()
                + " status=" + run.status()
                + " stopReason=" + run.stopReason()
                + " files=" + run.filesWritten()
                + " toolCalls=" + run.toolCalls()
                + " repairRounds=" + run.repairRounds()
                + " buildPassed=" + run.buildPassed()
                + " tokens=" + run.totalTokens()
                + " durationMs=" + run.durationMs()
                + (run.summary() == null ? "" : "\n" + run.summary());
        return content(report, !"SUCCEEDED".equals(run.status()));
    }

    private Map<String, Object> listFiles(JsonNode args) {
        Long projectId = args.path("project_id").asLong();
        // Ownership check first: throws if the project is not the service
        // account's, which is what turns a wrong project_id into a readable
        // tool error instead of someone else's file list.
        projects.getById(projectId, ownerId);
        var found = files.findByProjectIdOrderByPath(projectId);
        if (found.isEmpty()) {
            return content("Project " + projectId + " has no files yet. "
                    + "Call generate_app first.", false);
        }
        String listing = found.stream()
                .map(f -> f.getPath() + "  " + f.getSizeBytes() + " bytes")
                .collect(Collectors.joining("\n"));
        return content(found.size() + " file(s) in project " + projectId + ":\n" + listing, false);
    }
}
