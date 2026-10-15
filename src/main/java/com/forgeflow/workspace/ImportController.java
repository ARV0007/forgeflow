package com.forgeflow.workspace;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Bring an existing codebase in. Both routes replace the project's files
 * (the old ones stay in History), so both need WRITE.
 *
 * The zip goes up as the raw request body (Content-Type: application/zip),
 * not a multipart form: one file, no form, and the size cap is ours - read
 * from the stream, not configured in a multipart resolver.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/import")
public class ImportController {

    private final ProjectService projects;
    private final ProjectImporter importer;

    public ImportController(ProjectService projects, ProjectImporter importer) {
        this.projects = projects;
        this.importer = importer;
    }

    public record GithubImport(String url) {
    }

    @PostMapping("/zip")
    public ProjectImporter.Report zip(@PathVariable Long projectId,
                                      @RequestParam(defaultValue = "upload.zip") String name,
                                      HttpServletRequest request, Authentication auth) throws IOException {
        Long userId = (Long) auth.getPrincipal();
        projects.requireWrite(projectId, userId);
        String label = "Imported " + name.replaceAll("[^A-Za-z0-9._ -]", "").strip();
        // A refusal (not a zip, too big...) is a 400 with the reason, via BadRequestException.
        return importer.importZip(projectId, userId, request.getInputStream(), label);
    }

    @PostMapping("/github")
    public ProjectImporter.Report github(@PathVariable Long projectId, @RequestBody GithubImport body,
                                         Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        projects.requireWrite(projectId, userId);
        return importer.importGithub(projectId, userId, body == null ? null : body.url());
    }
}
