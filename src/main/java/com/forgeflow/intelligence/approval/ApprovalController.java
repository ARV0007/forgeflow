package com.forgeflow.intelligence.approval;

import com.forgeflow.workspace.ProjectService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** What the agent asked permission for, and the yes/no. Viewers can see requests; editors decide. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/approvals")
public class ApprovalController {

    private final Approvals approvals;
    private final ProjectService projects;

    public ApprovalController(Approvals approvals, ProjectService projects) {
        this.approvals = approvals;
        this.projects = projects;
    }

    @GetMapping
    public List<Approvals.Request> list(@PathVariable Long projectId,
                                        @RequestParam(defaultValue = "false") boolean pending, Authentication auth) {
        projects.getById(projectId, (Long) auth.getPrincipal());
        return approvals.list(projectId, pending);
    }

    @PostMapping("/{approvalId}/approve")
    public Approvals.Request approve(@PathVariable Long projectId, @PathVariable Long approvalId, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        projects.requireWrite(projectId, userId);
        return approvals.approve(projectId, approvalId, userId);
    }

    @PostMapping("/{approvalId}/reject")
    public Approvals.Request reject(@PathVariable Long projectId, @PathVariable Long approvalId, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        projects.requireWrite(projectId, userId);
        return approvals.reject(projectId, approvalId, userId);
    }
}
