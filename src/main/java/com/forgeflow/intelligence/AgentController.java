package com.forgeflow.intelligence;

import com.forgeflow.intelligence.dto.GenerateRequest;
import com.forgeflow.intelligence.dto.GenerateResponse;
import com.forgeflow.workspace.ProjectService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class AgentController {

    private final AgentService agent;
    private final ProjectService projects;

    public AgentController(AgentService agent, ProjectService projects) {
        this.agent = agent;
        this.projects = projects;
    }

    @PostMapping("/generate")
    public GenerateResponse generate(@PathVariable Long projectId,
                                     @Valid @RequestBody GenerateRequest request,
                                     Authentication auth) {
        Long userId = (Long) auth.getPrincipal();

        // Ownership check FIRST. Without this, anyone with a valid token could
        // spend tokens writing files into someone else's project. Throws 404.
        projects.getById(projectId, userId);

        return agent.generate(projectId, userId, request.prompt());
    }
}
