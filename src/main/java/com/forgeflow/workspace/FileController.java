package com.forgeflow.workspace;

import com.forgeflow.shared.ResourceNotFoundException;
import com.forgeflow.workspace.dto.FileEntry;
import com.forgeflow.workspace.dto.ProjectResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Spec: Files - File Tree, File Content, Download all files as zip.
 * All three are reads, so a VIEWER can use them.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/files")
public class FileController {

    private final ProjectFileService files;
    private final ProjectService projects;

    public FileController(ProjectFileService files, ProjectService projects) {
        this.files = files;
        this.projects = projects;
    }

    @GetMapping
    public List<FileEntry> tree(@PathVariable Long projectId, Authentication auth) {
        projects.getById(projectId, (Long) auth.getPrincipal());
        return files.list(projectId);
    }

    @GetMapping("/content")
    public Map<String, Object> content(@PathVariable Long projectId,
                                       @RequestParam String path,
                                       Authentication auth) {
        projects.getById(projectId, (Long) auth.getPrincipal());
        return files.read(projectId, path)
                .map(content -> Map.<String, Object>of("path", path, "content", content))
                .orElseThrow(() -> new ResourceNotFoundException("File not found: " + path));
    }

    /**
     * Built in memory rather than streamed: a generated project is a handful of
     * text files capped at 200 KB each, and a byte array lets a failure turn into
     * a proper error status instead of a half-written download.
     */
    @GetMapping("/download")
    public ResponseEntity<byte[]> download(@PathVariable Long projectId, Authentication auth) throws IOException {
        ProjectResponse project = projects.getById(projectId, (Long) auth.getPrincipal());
        String folder = slug(project.name(), projectId);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        files.writeZip(projectId, folder, buffer);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(folder + ".zip").build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(buffer.toByteArray());
    }

    /** "My Todo App!" -> "my-todo-app". Safe in a filename on every OS. */
    static String slug(String name, Long projectId) {
        String s = name == null ? "" : name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (s.length() > 60) {
            s = s.substring(0, 60).replaceAll("-+$", "");
        }
        return s.isEmpty() ? "project-" + projectId : s;
    }
}
