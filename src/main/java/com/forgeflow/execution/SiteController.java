package com.forgeflow.execution;

import com.forgeflow.execution.dto.PublishRequest;
import com.forgeflow.execution.dto.SiteResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Publish, and the published sites themselves.
 *
 *   POST   /api/v1/projects/{id}/site     publish now, or {checkpointId} to roll back (WRITE)
 *   GET    /api/v1/projects/{id}/site     what's published, and the release history (READ)
 *   DELETE /api/v1/projects/{id}/site     unpublish (WRITE)
 *   GET    /s/{slug}/**                    the site - public, no token
 *
 * A site is served from the same origin as the API, so it gets the same
 * Content-Security-Policy sandbox as a preview: generated code runs in an
 * opaque origin and can't read anyone's ForgeFlow session. React sites get
 * the same in-browser runner as the preview; they don't get the console
 * bridge - a published site's visitors aren't the project's developers.
 */
@RestController
public class SiteController {

    private final SiteService sites;

    public SiteController(SiteService sites) {
        this.sites = sites;
    }

    @PostMapping("/api/v1/projects/{projectId}/site")
    public SiteResponse publish(@PathVariable Long projectId, @RequestBody(required = false) PublishRequest req,
                                Authentication auth) {
        return sites.publish(projectId, (Long) auth.getPrincipal(), req == null ? null : req.checkpointId());
    }

    @GetMapping("/api/v1/projects/{projectId}/site")
    public SiteResponse get(@PathVariable Long projectId, Authentication auth) {
        return sites.get(projectId, (Long) auth.getPrincipal());
    }

    @DeleteMapping("/api/v1/projects/{projectId}/site")
    public ResponseEntity<Void> unpublish(@PathVariable Long projectId, Authentication auth) {
        sites.unpublish(projectId, (Long) auth.getPrincipal());
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------- public sites

    @GetMapping("/s/{slug}/**")
    public ResponseEntity<byte[]> serve(@PathVariable String slug, HttpServletRequest request) {
        Optional<SiteService.Live> live = sites.live(slug);
        if (live.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        SiteService.Live site = live.get();
        String full = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String prefix = "/s/" + slug + "/";
        String path = full != null && full.startsWith(prefix) ? full.substring(prefix.length()) : "";

        // The runner's own files and the React builds - same as the preview's.
        PreviewContentController.Resource vendored = PreviewContentController.VENDOR.get(path);
        if (vendored != null && !path.equals("__snapshot.js")) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                    .header("Access-Control-Allow-Origin", "*")
                    .contentType(MediaType.valueOf("text/javascript"))
                    .body(vendored.bytes());
        }
        if (path.equals("__files.json")) {
            return ResponseEntity.ok()
                    .header("Access-Control-Allow-Origin", "*")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(("[" + String.join(",", site.paths().stream().map(p -> "\"" + p.replace("\"", "\\\"") + "\"").toList())
                            + "]").getBytes(StandardCharsets.UTF_8));
        }

        if (path.isEmpty() || path.endsWith("/")) {
            path = path + "index.html";
        }
        Optional<String> body = sites.read(site, path);
        if (body.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        MediaType type = PreviewContentController.contentTypeOf(path);
        String content = body.get();
        if (MediaType.TEXT_HTML.equals(type) && site.paths().contains("package.json")) {
            content = PreviewContentController.withModuleRunner(content, prefix + "__runner.js");
        }
        return ResponseEntity.ok()
                .header("Content-Security-Policy", PreviewContentController.SANDBOX_CSP)
                .header("Access-Control-Allow-Origin", "*")
                .header("X-Content-Type-Options", "nosniff")
                // Short: the slug can be moved to another version at any moment.
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=30")
                .header("X-ForgeFlow-Version", String.valueOf(site.checkpointId()))
                .contentType(type)
                .body(content.getBytes(StandardCharsets.UTF_8));
    }
}
