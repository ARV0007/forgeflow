package com.forgeflow.execution;

import com.forgeflow.workspace.ProjectFileService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves a project's generated files, for environments with no container to
 * serve them from - and reports what happens to them into the project's log.
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
    private static final Pattern HEAD_OPEN = Pattern.compile("<head(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);

    /**
     * Injected at the top of every HTML page the preview serves. It does two
     * jobs:
     *
     *  - forwards console output and uncaught errors to /p/{token}/__log, which
     *    is how the generated app's runtime errors reach the Logs Stream;
     *  - answers "ff:snapshot" messages from the page that embeds the preview
     *    (and only from it - e.source must be the parent window) with a JPEG
     *    of the page, which is how the visual review gets its screenshot.
     *    html-to-image is loaded only when asked, from /p/{token}/__snapshot.js.
     *    (Not html2canvas: it clones the page into a child iframe, and in a
     *    sandboxed opaque origin that child is a different origin it may not
     *    touch. html-to-image clones in place and renders through an SVG
     *    foreignObject, which works inside the sandbox.) While a snapshot is
     *    being taken the console bridge is muted, so the library's own
     *    warnings never show up as the app's errors.
     *
     * sendBeacon posts text/plain, a "simple" request, so it needs no CORS
     * preflight from the sandbox's opaque origin; the page never reads the
     * response, so nothing about our origin leaks back in. Every call is
     * wrapped: a broken logger must never break the app it is watching.
     */
    private static final String CONSOLE_BRIDGE = """
            <script>(function(){var u="__URL__",q=0;function f(x){try{if(x instanceof Error)return x.stack||x.message;\
            if(typeof x==="object")return JSON.stringify(x);return String(x)}catch(e){return String(x)}}\
            function s(l,a){try{var m=Array.prototype.map.call(a,f).join(" ").slice(0,2000);\
            var b=JSON.stringify({level:l,message:m});if(navigator.sendBeacon){navigator.sendBeacon(u,b)}\
            else{fetch(u,{method:"POST",body:b,mode:"no-cors",keepalive:true})}}catch(e){}}\
            ["log","info","warn","error"].forEach(function(l){var o=console[l];console[l]=function(){if(!q)s(l,arguments);\
            return o.apply(console,arguments)}});addEventListener("error",function(e){\
            s("error",[(e.message||"Error")+" ("+(e.filename||"?").split("/").pop()+":"+(e.lineno||0)+")"])});\
            addEventListener("unhandledrejection",function(e){var r=e.reason;\
            s("error",["Unhandled promise rejection: "+(r&&(r.stack||r.message)||r)])});\
            addEventListener("message",function(e){var d=e.data;if(!d||d.type!=="ff:snapshot"||e.source!==parent)return;\
            function done(r){q=0;r.type="ff:snapshot";r.id=d.id;try{parent.postMessage(r,"*")}catch(x){}}\
            function go(){q=1;var h=Math.min(Math.max(document.documentElement.scrollHeight,innerHeight),2000);\
            htmlToImage.toJpeg(document.documentElement,{quality:0.82,backgroundColor:"#fff",skipFonts:true,\
            width:innerWidth,height:h,pixelRatio:Math.min(1,1024/innerWidth)}).then(function(u){done({data:u})})\
            ["catch"](function(x){done({error:String(x&&x.message||x)})})}\
            try{if(window.htmlToImage)go();else{var t=document.createElement("script");t.src="__SNAP__";t.onload=go;\
            t.onerror=function(){done({error:"snapshot library did not load"})};document.head.appendChild(t)}}\
            catch(x){done({error:String(x)})}})})();</script>""";

    private final PreviewRepository previews;
    private final ProjectFileService files;
    private final PreviewLogs logs;
    private final ObjectMapper json = new ObjectMapper();

    public PreviewContentController(PreviewRepository previews, ProjectFileService files, PreviewLogs logs) {
        this.previews = previews;
        this.files = files;
        this.logs = logs;
    }

    @GetMapping("/p/{token}/**")
    public ResponseEntity<byte[]> serve(@PathVariable String token, HttpServletRequest request) {
        Preview preview = live(token).orElse(null);
        if (preview == null) {
            return ResponseEntity.notFound().build();
        }
        Long projectId = preview.getProjectId();

        String full = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String prefix = "/p/" + token + "/";
        String path = full != null && full.startsWith(prefix) ? full.substring(prefix.length()) : "";
        if (path.isEmpty()) {
            path = "index.html";
        }

        Optional<String> body = files.read(projectId, path);
        if (body.isEmpty()) {
            logs.warn(projectId, "http", "GET /" + path + " 404 - no such file in the project");
            return ResponseEntity.notFound().build();
        }
        logs.info(projectId, "http", "GET /" + path + " 200");

        MediaType type = contentTypeOf(path);
        String content = MediaType.TEXT_HTML.equals(type)
                ? withConsoleBridge(body.get(), "/p/" + token + "/__log")
                : body.get();

        return ResponseEntity.ok()
                .header("Content-Security-Policy", SANDBOX_CSP)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(type)
                .body(content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Where the console bridge reports to. Open to anyone holding the token -
     * the same people who can load the preview - and that is acceptable
     * because the worst a forger can do is add lines to a log, and
     * PreviewLogs rate-caps those per project.
     */
    @PostMapping("/p/{token}/__log")
    public ResponseEntity<Void> report(@PathVariable String token, @RequestBody(required = false) String body) {
        Preview preview = live(token).orElse(null);
        if (preview == null) {
            return ResponseEntity.notFound().build();
        }
        if (body == null || body.length() > 8_000) {
            return ResponseEntity.badRequest().build();
        }
        JsonNode node;
        try {
            node = json.readTree(body);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
        String message = node.path("message").asText("");
        if (message.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        logs.fromConsole(preview.getProjectId(), node.path("level").asText("log"), message);
        return ResponseEntity.noContent().build();
    }

    /**
     * html-to-image 1.11.13 (MIT), vendored rather than loaded from a CDN: the
     * preview must work offline and in locked-down networks, and a page we
     * serve should not pull code from a third party to photograph itself.
     * Read once, cached by the browser for a day.
     */
    @GetMapping("/p/{token}/__snapshot.js")
    public ResponseEntity<byte[]> snapshotLibrary(@PathVariable String token) {
        if (live(token).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.valueOf("text/javascript"))
                .body(SnapshotLibrary.BYTES);
    }

    /** Loaded on first use; a missing resource is a packaging bug, so it fails loudly. */
    private static final class SnapshotLibrary {
        static final byte[] BYTES;
        static {
            try (var in = PreviewContentController.class.getResourceAsStream("/preview/html-to-image.min.js")) {
                if (in == null) {
                    throw new IllegalStateException("preview/html-to-image.min.js missing from the classpath");
                }
                BYTES = in.readAllBytes();
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }

    /** A preview the token currently opens: RUNNING and not past its expiry. */
    private Optional<Preview> live(String token) {
        return previews.findByContainerIdAndStatus(token, "RUNNING").stream()
                .filter(p -> p.getExpiresAt() == null || p.getExpiresAt().isAfter(Instant.now()))
                .findFirst();
    }

    /** Insert the bridge as early as possible, so it is in place before the page's own scripts run. */
    static String withConsoleBridge(String html, String logUrl) {
        String script = CONSOLE_BRIDGE.replace("__URL__", logUrl)
                .replace("__SNAP__", logUrl.endsWith("__log")
                        ? logUrl.substring(0, logUrl.length() - "__log".length()) + "__snapshot.js"
                        : "__snapshot.js");
        Matcher head = HEAD_OPEN.matcher(html);
        if (head.find()) {
            return html.substring(0, head.end()) + script + html.substring(head.end());
        }
        return script + html;
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
}
