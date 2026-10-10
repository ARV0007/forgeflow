package com.forgeflow.execution;

import com.forgeflow.workspace.ProjectFileService;
import com.forgeflow.workspace.dto.FileEntry;
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
import java.util.List;
import java.util.Map;
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
    /** A whole <script ...>...</script> element, for swapping module entries out of a React page. */
    private static final Pattern SCRIPT_ELEMENT =
            Pattern.compile("<script\\b([^>]*)>\\s*</script>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TYPE_MODULE = Pattern.compile("\\btype\\s*=\\s*['\"]module['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern SRC_ATTR = Pattern.compile("\\bsrc\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);

    /** Files the preview serves from the classpath, by the name the runner asks for. */
    private static final Map<String, Resource> VENDOR = Map.of(
            "__snapshot.js", new Resource("/preview/html-to-image.min.js"),
            "__runner.js", new Resource("/preview/module-runner.js"),
            "__vendor/react.js", new Resource("/preview/react.production.min.js"),
            "__vendor/react-dom.js", new Resource("/preview/react-dom.production.min.js"));

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
        String content = body.get();
        if (MediaType.TEXT_HTML.equals(type)) {
            if (files.read(projectId, "package.json").isPresent()) {
                content = withModuleRunner(content, "/p/" + token + "/__runner.js");
            }
            content = withConsoleBridge(content, "/p/" + token + "/__log");
        }

        return ResponseEntity.ok()
                .header("Content-Security-Policy", SANDBOX_CSP)
                // The React runner fetch()es the sources from the sandbox's opaque
                // origin, which is cross-origin to us. Nothing here is secret
                // from someone holding the token: they can already load it all.
                .header("Access-Control-Allow-Origin", "*")
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
     * The preview's own scripts, from the classpath - vendored rather than
     * loaded from a CDN, so a preview works offline and in locked-down
     * networks, and a page we serve pulls no code from a third party:
     *
     *   __snapshot.js        html-to-image 1.11.13 (MIT) - the visual check's camera
     *   __runner.js          the in-browser React runner (preview-runner/, Sucrase inside)
     *   __vendor/react.js    React 18.3.1 UMD
     *   __vendor/react-dom.js
     *
     * Rebuilt by preview-runner/build.sh. Cached by the browser for a day.
     */
    @GetMapping({"/p/{token}/__snapshot.js", "/p/{token}/__runner.js", "/p/{token}/__vendor/{name}"})
    public ResponseEntity<byte[]> vendored(@PathVariable String token, HttpServletRequest request) {
        if (live(token).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String full = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        Resource r = full == null ? null : VENDOR.get(full.substring(("/p/" + token + "/").length()));
        if (r == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .header("X-Content-Type-Options", "nosniff")
                .header("Access-Control-Allow-Origin", "*")
                .contentType(MediaType.valueOf("text/javascript"))
                .body(r.bytes());
    }

    /** The project's file list, for the React runner to resolve './Card' to src/Card.jsx without guessing. */
    @GetMapping("/p/{token}/__files.json")
    public ResponseEntity<List<String>> fileList(@PathVariable String token) {
        Preview preview = live(token).orElse(null);
        if (preview == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Access-Control-Allow-Origin", "*")
                .body(files.list(preview.getProjectId()).stream().map(FileEntry::path).toList());
    }

    /** A classpath file, read on first use; a missing one is a packaging bug, so it fails loudly. */
    private static final class Resource {
        private final String name;
        private volatile byte[] bytes;

        Resource(String name) {
            this.name = name;
        }

        byte[] bytes() {
            if (bytes == null) {
                try (var in = PreviewContentController.class.getResourceAsStream(name)) {
                    if (in == null) {
                        throw new IllegalStateException(name + " missing from the classpath - run preview-runner/build.sh");
                    }
                    bytes = in.readAllBytes();
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
            return bytes;
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

    /**
     * A React page: each <script type="module" src="..."> becomes an inert
     * <script type="ff-module" data-src="...">, and the runner - which compiles
     * and starts them - goes in at the top of the head. Left as type="module",
     * the browser would fetch /src/main.jsx itself and choke on the JSX.
     */
    static String withModuleRunner(String html, String runnerUrl) {
        Matcher m = SCRIPT_ELEMENT.matcher(html);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String attrs = m.group(1);
            Matcher src = SRC_ATTR.matcher(attrs);
            if (TYPE_MODULE.matcher(attrs).find() && src.find() && !src.group(1).matches("(?i)^([a-z]+:)?//.*")) {
                m.appendReplacement(out, Matcher.quoteReplacement(
                        "<script type=\"ff-module\" data-src=\"" + src.group(1).replace("\"", "&quot;") + "\"></script>"));
            } else {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(out);
        String page = out.toString();
        String script = "<script src=\"" + runnerUrl + "\"></script>";
        Matcher head = HEAD_OPEN.matcher(page);
        return head.find()
                ? page.substring(0, head.end()) + script + page.substring(head.end())
                : script + page;
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
