package com.forgeflow.intelligence.audit;

import com.forgeflow.intelligence.AgentService;
import com.forgeflow.intelligence.dto.GenerateResponse;
import com.forgeflow.workspace.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit agent in the API. Starting one, fixing a finding and the nightly
 * switch need edit rights (they spend tokens or change files); reading
 * results only needs to see the project.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class AuditController {

    private final AuditAgent audits;
    private final ProjectService projects;
    private final AgentService agent;

    public AuditController(AuditAgent audits, ProjectService projects, AgentService agent) {
        this.audits = audits;
        this.projects = projects;
        this.agent = agent;
    }

    public record Settings(boolean nightly) {
    }

    public record FixResult(AuditAgent.Finding finding, GenerateResponse run) {
    }

    /** Starts in the background; poll /audits/latest. An audit already running is returned instead of a second one. */
    @PostMapping("/audits")
    public ResponseEntity<AuditAgent.Audit> start(@PathVariable Long projectId, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        projects.requireWrite(projectId, userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(audits.start(projectId, userId, "MANUAL"));
    }

    @GetMapping("/audits/latest")
    public ResponseEntity<AuditAgent.Audit> latest(@PathVariable Long projectId, Authentication auth) {
        projects.getById(projectId, (Long) auth.getPrincipal());
        return audits.latest(projectId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/audits/{auditId}")
    public AuditAgent.Audit get(@PathVariable Long projectId, @PathVariable Long auditId, Authentication auth) {
        projects.getById(projectId, (Long) auth.getPrincipal());
        return audits.get(projectId, auditId);
    }

    /** One click: an ordinary build run with the finding as the request. Marked fixed if the run succeeds. */
    @PostMapping("/audits/findings/{findingId}/fix")
    public FixResult fix(@PathVariable Long projectId, @PathVariable Long findingId, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        projects.requireWrite(projectId, userId);
        AuditAgent.Finding f = audits.finding(projectId, findingId);
        if (!"OPEN".equals(f.status())) {
            throw new com.forgeflow.shared.BadRequestException("That finding is already " + f.status().toLowerCase() + ".");
        }
        GenerateResponse run = agent.generate(projectId, userId, AuditAgent.fixPrompt(f));
        if ("SUCCEEDED".equals(run.status())) {
            audits.markFixed(projectId, findingId, run.runId());
        }
        return new FixResult(audits.finding(projectId, findingId), run);
    }

    @PostMapping("/audits/findings/{findingId}/dismiss")
    public AuditAgent.Finding dismiss(@PathVariable Long projectId, @PathVariable Long findingId, Authentication auth) {
        projects.requireWrite(projectId, (Long) auth.getPrincipal());
        return audits.dismiss(projectId, findingId);
    }

    @GetMapping("/audit-settings")
    public Settings settings(@PathVariable Long projectId, Authentication auth) {
        projects.getById(projectId, (Long) auth.getPrincipal());
        return new Settings(audits.nightly(projectId));
    }

    @PutMapping("/audit-settings")
    public Settings update(@PathVariable Long projectId, @RequestBody Settings body, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        projects.requireWrite(projectId, userId);
        audits.setNightly(projectId, body != null && body.nightly(), userId);
        return new Settings(audits.nightly(projectId));
    }
}
