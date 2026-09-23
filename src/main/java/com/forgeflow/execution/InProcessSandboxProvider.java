package com.forgeflow.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The sandbox for environments that cannot run containers.
 *
 * Free hosting runs your application INSIDE a container and gives you no Docker
 * daemon, so DockerSandboxProvider cannot work there. This implementation does
 * the same job with weaker guarantees, and says so rather than pretending
 * otherwise:
 *
 *   build   - structural checks in this JVM instead of a locked-down container
 *   preview - files served by Spring under an unguessable token, instead of nginx
 *
 * Selected with forgeflow.sandbox.provider=in-process.
 */
@Component
@ConditionalOnProperty(name = "forgeflow.sandbox.provider", havingValue = "in-process")
public class InProcessSandboxProvider implements SandboxProvider {

    private static final Logger log = LoggerFactory.getLogger(InProcessSandboxProvider.class);

    /** src= / href= pointing at something local. */
    private static final Pattern LOCAL_REF =
            Pattern.compile("(?:src|href)\\s*=\\s*[\"']([^\"'#?]+)[^\"']*[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern ABSOLUTE =
            Pattern.compile("^([a-z]+:)?//|^(data|mailto|tel|javascript):", Pattern.CASE_INSENSITIVE);

    /** projectId -> token, so a project has one live preview at a time. */
    private final Map<Long, String> tokens = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public BuildResult build(Long projectId, Map<String, String> files) {
        long started = System.currentTimeMillis();

        if (files.isEmpty()) {
            return new BuildResult(false, -1, "The project has no files yet.", 0, false);
        }

        List<String> problems = new ArrayList<>();

        if (!files.containsKey("index.html")) {
            problems.add("index.html is missing - every project needs an entry point");
        }

        for (Map.Entry<String, String> file : files.entrySet()) {
            if (file.getKey().endsWith(".js")) {
                problems.addAll(StructuralJsCheck.scan(file.getKey(), file.getValue()));
            }
        }

        for (Map.Entry<String, String> file : files.entrySet()) {
            if (!file.getKey().endsWith(".html")) {
                continue;
            }
            Matcher m = LOCAL_REF.matcher(file.getValue());
            while (m.find()) {
                String ref = m.group(1).trim();
                if (ref.isEmpty() || ABSOLUTE.matcher(ref).find()) {
                    continue;
                }
                String target = resolve(file.getKey(), ref);
                if (target == null || !files.containsKey(target)) {
                    problems.add(file.getKey() + " references a file that does not exist: " + ref);
                }
            }
        }

        long took = System.currentTimeMillis() - started;
        boolean passed = problems.isEmpty();

        String output = passed
                ? "OK - " + files.size() + " file(s) checked (in-process: structure only, no container)"
                : String.join("\n", problems);

        log.info("in-process build project {} {} in {} ms", projectId, passed ? "PASSED" : "FAILED", took);
        return new BuildResult(passed, passed ? 0 : 1, output, took, false);
    }

    @Override
    public PreviewHandle startPreview(Long projectId, Map<String, String> files) {
        if (files.isEmpty()) {
            throw new SandboxException("The project has no files to preview.");
        }
        // An unguessable token, not the project id. Previews are shareable
        // links, so the URL must not let anyone walk the ID space and read
        // other people's projects.
        String token = UUID.randomUUID().toString().replace("-", "");
        tokens.put(projectId, token);
        return new PreviewHandle(projectId, token, "/p/" + token + "/index.html");
    }

    @Override
    public void stopPreview(Long projectId) {
        tokens.remove(projectId);
    }

    /** Resolves a relative reference against the referring file, or null if it escapes. */
    private String resolve(String fromPath, String ref) {
        String dir = fromPath.contains("/") ? fromPath.substring(0, fromPath.lastIndexOf('/') + 1) : "";
        String joined = ref.startsWith("/") ? ref.substring(1) : dir + ref;

        List<String> parts = new ArrayList<>();
        for (String segment : joined.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (parts.isEmpty()) {
                    return null;          // escapes the project
                }
                parts.remove(parts.size() - 1);
                continue;
            }
            parts.add(segment);
        }
        return String.join("/", parts);
    }
}
