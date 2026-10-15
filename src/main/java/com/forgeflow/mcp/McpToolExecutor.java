package com.forgeflow.mcp;

import com.forgeflow.billing.BillingService;
import com.forgeflow.account.ServiceAccounts;
import com.forgeflow.intelligence.AgentService;
import com.forgeflow.intelligence.retrieval.ProjectQa;
import com.forgeflow.workspace.ProjectFileService;
import com.forgeflow.workspace.ProjectService;
import com.forgeflow.workspace.Stack;
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
 * MCP requests carry no JWT, so everything here acts as one service account,
 * found by email and created on first use (see ServiceAccounts). Projects made
 * through MCP belong to that account, not to whoever is driving the client.
 */
@Component
public class McpToolExecutor {

    private final ProjectService projects;
    private final AgentService agent;
    private final ProjectFileService files;
    private final ServiceAccounts serviceAccounts;
    private final BillingService billing;
    private final ProjectQa qa;
    private final String serviceAccountEmail;

    /** Resolved on first use, then fixed for the life of the process. */
    private volatile Long ownerId;

    public McpToolExecutor(ProjectService projects,
                           AgentService agent,
                           ProjectFileService files,
                           ServiceAccounts serviceAccounts,
                           BillingService billing,
                           ProjectQa qa,
                           @Value("${forgeflow.mcp.service-account-email}") String serviceAccountEmail) {
        this.projects = projects;
        this.agent = agent;
        this.files = files;
        this.serviceAccounts = serviceAccounts;
        this.billing = billing;
        this.qa = qa;
        this.serviceAccountEmail = serviceAccountEmail;
    }

    /**
     * The identity every MCP call acts as. Provisioned lazily rather than at
     * startup, so a database that is briefly unreachable cannot stop the app
     * from booting.
     */
    private Long ownerId() {
        Long id = ownerId;
        if (id == null) {
            synchronized (this) {
                if (ownerId == null) {
                    Long id0 = serviceAccounts.ensure(serviceAccountEmail, "MCP service account");
                    billing.ensureInternalPlan(id0);
                    ownerId = id0;
                }
                id = ownerId;
            }
        }
        return id;
    }

    /** A tool result: text the model reads, plus whether it went wrong. */
    static Map<String, Object> content(String text, boolean isError) {
        return Map.of(
                "content", List.of(Map.of("type", "text", "text", text)),
                "isError", isError
        );
    }

    public Map<String, Object> call(String name, JsonNode args) {
        return call(name, args, null);
    }

    /**
     * @param as the user to act as - the owner of the personal access token
     *           the caller presented; null = the shared MCP service account
     */
    public Map<String, Object> call(String name, JsonNode args, Long as) {
        try {
            Long who = as != null ? as : ownerId();
            return switch (name) {
                case "create_project" -> createProject(args, who);
                case "generate_app" -> generateApp(args, who);
                case "list_project_files" -> listFiles(args, who);
                case "ask_project" -> askProject(args, who);
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

    private Map<String, Object> createProject(JsonNode args, Long who) {
        String name = args.path("name").asText();
        String desc = args.path("description").asText();
        Stack stack = "react".equalsIgnoreCase(args.path("stack").asText()) ? Stack.REACT : Stack.STATIC;
        var p = projects.create(who, new CreateProjectRequest(name, desc, stack));
        return content("Created " + (stack == Stack.REACT ? "React " : "") + "project " + p.id() + " (\"" + p.name() + "\"). "
                + "Use this project_id with generate_app.", false);
    }

    private Map<String, Object> generateApp(JsonNode args, Long who) {
        Long projectId = args.path("project_id").asLong();
        String prompt = args.path("prompt").asText();
        // AgentService does no access check of its own - AgentController does
        // it, and MCP does not go through AgentController. Without this line an
        // MCP caller could write into any project id it guessed.
        projects.requireWrite(projectId, who);
        var run = agent.generate(projectId, who, prompt);
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

    private Map<String, Object> listFiles(JsonNode args, Long who) {
        Long projectId = args.path("project_id").asLong();
        // Ownership check first: throws if the project is not the service
        // account's, which is what turns a wrong project_id into a readable
        // tool error instead of someone else's file list.
        projects.getById(projectId, who);
        var found = files.list(projectId);
        if (found.isEmpty()) {
            return content("Project " + projectId + " has no files yet. "
                    + "Call generate_app first.", false);
        }
        String listing = found.stream()
                .map(f -> f.path() + "  " + f.sizeBytes() + " bytes")
                .collect(Collectors.joining("\n"));
        return content(found.size() + " file(s) in project " + projectId + ":\n" + listing, false);
    }

    private Map<String, Object> askProject(JsonNode args, Long who) {
        Long projectId = args.path("project_id").asLong();
        String question = args.path("question").asText("").strip();
        if (question.isEmpty()) {
            return content("question is required - what do you want to know about the code?", true);
        }
        projects.getById(projectId, who);
        ProjectQa.Answer a = qa.ask(who, projectId, question);
        StringBuilder sb = new StringBuilder(a.answer());
        if (!a.citations().isEmpty()) {
            sb.append("\n\nSources:");
            for (ProjectQa.Source c : a.citations()) {
                sb.append("\n[").append(c.n()).append("] ").append(c.path())
                  .append(" lines ").append(c.startLine()).append('-').append(c.endLine());
            }
        }
        return content(sb.toString(), false);
    }
}
