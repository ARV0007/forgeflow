package com.forgeflow.execution;

import com.forgeflow.workspace.ProjectFileService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Serves a project's generated files, for environments with no container to
 * serve them from.
 *
 * Three things make this safe enough to expose without a token in the header:
 *
 *  1. The URL carries an unguessable token, not the project id, so nobody can
 *     walk the ID space and read other people's work.
 *  2. The token only resolves while a preview is RUNNING and unexpired, so
 *     stopping a preview revokes the link.
 *  3. Every response carries a Content-Security-Policy sandbox directive.
 *     THIS ONE MATTERS: the files are HTML and JavaScript written by a model in
 *     response to a stranger's prompt, and they are being served from OUR
 *     origin. Without the directive that script could read this origin's
 *     localStorage - where the signed-in user's JWT lives. "sandbox" without
 *     "allow-same-origin" drops the document into an opaque origin, so it can
 *     run but can see nothing of ours.
 */
@RestController
public class PreviewContentController {

    private static final String SANDBOX_CSP = "sandbox allow-scripts allow-forms allow-popups";

    private final PreviewRepository previews;
    private final ProjectFileService files;

    public PreviewContentController(PreviewRepository previews, ProjectFileService files) {
        this.previews = previews;
        this.files = files;
    }

    @GetMapping("/p/{token}/**")
    public ResponseEntity<byte[]> serve(@PathVariable String token, HttpServletRequest request) {

        Preview preview = previews.findByContainerIdAndStatus(token, "RUNNING").stream()
                .findFirst()
                .orElse(null);

        if (preview == null
                || (preview.getExpiresAt() != null && preview.getExpiresAt().isBefore(Instant.now()))) {
            return ResponseEntity.notFound().build();
        }

        String full = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String prefix = "/p/" + token + "/";
        String path = full != null && full.startsWith(prefix) ? full.substring(prefix.length()) : "";
        if (path.isEmpty()) {
            path = "index.html";
        }

        Map<String, String> snapshot = files.snapshot(preview.getProjectId());
        String body = snapshot.get(path);
        if (body == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .header("Content-Security-Policy", SANDBOX_CSP)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(contentTypeOf(path))
                .body(body.getBytes(StandardCharsets.UTF_8));
    }

    private MediaType contentTypeOf(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return MediaType.TEXT_HTML;
        if (lower.endsWith(".css"))  return MediaType.valueOf("text/css");
        if (lower.endsWith(".js"))   return MediaType.valueOf("text/javascript");
        if (lower.endsWith(".json")) return MediaType.APPLICATION_JSON;
        if (lower.endsWith(".svg"))  return MediaType.valueOf("image/svg+xml");
        return MediaType.TEXT_PLAIN;
    }

    /** Unused, kept so the import of List is meaningful if paging is added. */
    private static final List<String> TEXT_TYPES = List.of("html", "css", "js", "json", "svg", "txt");
}
