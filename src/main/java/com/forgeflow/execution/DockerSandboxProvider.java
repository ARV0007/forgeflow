package com.forgeflow.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs the sandbox by shelling out to the docker CLI.
 *
 * No docker-java library on purpose - same reasoning as skipping Spring AI:
 * one less dependency to version-match against Boot 4, and the exact flags
 * that make up the security model stay visible right here in the code.
 */
@Component
@ConditionalOnProperty(name = "forgeflow.sandbox.provider", havingValue = "docker",
        matchIfMissing = true)
public class DockerSandboxProvider implements SandboxProvider {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxProvider.class);
    private static final int MAX_OUTPUT_BYTES = 8_000;

    private final String docker;
    private final String buildImage;
    private final String previewImage;
    private final String cpuLimit;
    private final int memoryMb;
    private final int pidsLimit;
    private final long timeoutSeconds;
    private final Path workRoot;
    private final String checkScript;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public DockerSandboxProvider(
            @Value("${forgeflow.sandbox.docker-binary:docker}") String docker,
            @Value("${forgeflow.sandbox.build-image:}") String buildImage,
            @Value("${forgeflow.sandbox.preview-image:}") String previewImage,
            @Value("${forgeflow.sandbox.cpu-limit:0.5}") String cpuLimit,
            @Value("${forgeflow.sandbox.memory-mb:512}") int memoryMb,
            @Value("${forgeflow.sandbox.pids-limit:128}") int pidsLimit,
            @Value("${forgeflow.sandbox.timeout-seconds:30}") long timeoutSeconds,
            @Value("${forgeflow.sandbox.work-dir:}") String workDir) throws IOException {

        this.docker = docker;
        // Image defaults live here, not in @Value: an image tag like
        // "node:20-alpine" contains ':' - which is also Spring's separator
        // between a placeholder key and its default value.
        this.buildImage = buildImage.isBlank() ? "node:20-alpine" : buildImage;
        this.previewImage = previewImage.isBlank() ? "nginx:alpine" : previewImage;
        this.cpuLimit = cpuLimit;
        this.memoryMb = memoryMb;
        this.pidsLimit = pidsLimit;
        this.timeoutSeconds = timeoutSeconds;
        // Under the home directory, because Docker Desktop on a Mac can only
        // bind-mount paths it shares with its VM, and /Users is always shared.
        this.workRoot = workDir.isBlank()
                ? Path.of(System.getProperty("user.home"), ".forgeflow")
                : Path.of(workDir);
        Files.createDirectories(this.workRoot);
        this.checkScript = new ClassPathResource("sandbox/check.js")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ build

    @Override
    public BuildResult build(Long projectId, Map<String, String> files) {
        long started = System.currentTimeMillis();

        if (files.isEmpty()) {
            return new BuildResult(false, -1, "The project has no files yet.", 0, false);
        }

        String name = "ff-build-" + projectId + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path work = workRoot.resolve("builds").resolve(name);

        try {
            writeFiles(work.resolve("app"), files);
            Files.writeString(work.resolve("check.js"), checkScript, StandardCharsets.UTF_8);
            makeReadable(work);

            List<String> cmd = List.of(docker, "run", "--rm",
                    "--name", name,
                    "--network=none",                    // cannot phone home
                    "--read-only",                       // cannot persist anything
                    "--tmpfs", "/tmp:rw,size=16m",
                    "--memory=" + memoryMb + "m",        // cannot eat the host's RAM
                    "--cpus=" + cpuLimit,                // cannot mine crypto on it
                    "--pids-limit=" + pidsLimit,         // cannot fork-bomb
                    "--cap-drop=ALL",                    // no Linux capabilities at all
                    "--security-opt=no-new-privileges",  // and cannot gain any
                    "--user=1000:1000",                  // not root
                    "-v", work.toAbsolutePath() + ":/work:ro",
                    buildImage,
                    "node", "/work/check.js", "/work/app");

            ProcResult r = run(cmd, timeoutSeconds);

            if (r.timedOut()) {
                // Killing the docker CLI process does NOT kill the container.
                // Without this, a runaway build keeps burning CPU long after
                // we have given up on it.
                forceRemove(name);
            }

            boolean passed = !r.timedOut() && r.exitCode() == 0;
            long took = System.currentTimeMillis() - started;
            log.info("build project {} {} in {} ms", projectId, passed ? "PASSED" : "FAILED", took);
            return new BuildResult(passed, r.exitCode(), r.output().trim(), took, r.timedOut());

        } catch (IOException e) {
            return new BuildResult(false, -1, "Sandbox error: " + e.getMessage(),
                    System.currentTimeMillis() - started, false);
        } finally {
            deleteQuietly(work);
        }
    }

    // ---------------------------------------------------------------- preview

    @Override
    public PreviewHandle startPreview(Long projectId, Map<String, String> files) {
        if (files.isEmpty()) {
            throw new SandboxException("The project has no files to preview.");
        }

        String name = previewName(projectId);
        Path dir = workRoot.resolve("previews").resolve(String.valueOf(projectId));

        try {
            forceRemove(name);
            deleteQuietly(dir);
            writeFiles(dir, files);
            makeReadable(dir);

            // Lighter than the build box, deliberately. nginx only SERVES bytes;
            // the generated JavaScript runs in the viewer's browser, inside the
            // browser's own sandbox. The build container is the one that
            // processes generated code, so it carries the full lockdown.
            List<String> cmd = List.of(docker, "run", "-d",
                    "--name", name,
                    "-p", "127.0.0.1::80",   // this machine only, random free port
                    "--read-only",
                    "--tmpfs", "/var/cache/nginx",
                    "--tmpfs", "/var/run",
                    "--tmpfs", "/tmp",
                    "--memory=128m",
                    "--cpus=0.25",
                    "--pids-limit=64",
                    "-v", dir.toAbsolutePath() + ":/usr/share/nginx/html:ro",
                    previewImage);

            ProcResult started = run(cmd, 60);
            if (started.exitCode() != 0) {
                throw new SandboxException("Preview container failed to start: " + started.output());
            }

            String url = "http://127.0.0.1:" + hostPortOf(name) + "/";
            waitUntilServing(url, name);
            log.info("preview for project {} at {}", projectId, url);
            return new PreviewHandle(projectId, name, url);

        } catch (IOException e) {
            throw new SandboxException("Could not start preview: " + e.getMessage(), e);
        }
    }

    @Override
    public void stopPreview(Long projectId) {
        forceRemove(previewName(projectId));
        deleteQuietly(workRoot.resolve("previews").resolve(String.valueOf(projectId)));
    }

    private String previewName(Long projectId) {
        return "ff-preview-" + projectId;
    }

    /** docker port prints e.g. "127.0.0.1:55123" */
    private String hostPortOf(String container) throws IOException {
        ProcResult r = run(List.of(docker, "port", container, "80/tcp"), 15);
        String line = r.output().trim().split("\\R")[0];
        int colon = line.lastIndexOf(':');
        if (r.exitCode() != 0 || colon < 0) {
            throw new SandboxException("Could not read preview port: " + r.output());
        }
        return line.substring(colon + 1).trim();
    }

    private void waitUntilServing(String url, String container) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(2)).GET().build();
        for (int i = 0; i < 30; i++) {
            try {
                if (http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() < 500) {
                    return;
                }
            } catch (IOException ignored) {
                // not listening yet
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            sleepQuietly(200);
        }
        // Never came up. The container still exists (no --rm on previews, on
        // purpose), so its logs can say why instead of us guessing.
        String logs = run(List.of(docker, "logs", "--tail", "30", container), 15).output();
        forceRemove(container);
        throw new SandboxException("Preview never started serving. Container logs:\n" + logs);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Writes files under root, refusing any path that would escape it.
     *
     * The agent's path guard already protected the database. This is a second,
     * independent check, because a path that reaches this method is about to
     * become a real file on a real disk - and one layer of defence is exactly
     * one bug away from none.
     */
    private void writeFiles(Path root, Map<String, String> files) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        Files.createDirectories(base);
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path target = base.resolve(e.getKey()).normalize();
            if (!target.startsWith(base) || target.equals(base)) {
                throw new SandboxException("Refusing to write outside the sandbox: " + e.getKey());
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, e.getValue(), StandardCharsets.UTF_8);
        }
    }

    /** The container runs as uid 1000, not as you, so it needs read access. */
    private void makeReadable(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.forEach(p -> {
                try {
                    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(
                            Files.isDirectory(p) ? "rwxr-xr-x" : "rw-r--r--"));
                } catch (IOException | UnsupportedOperationException ignored) {
                    // non-POSIX filesystem; nothing to do
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    private void deleteQuietly(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    private void forceRemove(String container) {
        try {
            run(List.of(docker, "rm", "-f", container), 15);
        } catch (IOException ignored) {
            // nothing to remove
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record ProcResult(int exitCode, String output, boolean timedOut) {
    }

    private ProcResult run(List<String> cmd, long timeoutSecs) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        // Drain output on a separate thread. A child that fills its pipe buffer
        // blocks forever if we only start reading after waitFor() returns.
        Thread drainer = Thread.ofVirtual().start(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    synchronized (captured) {
                        int room = MAX_OUTPUT_BYTES - captured.size();
                        if (room > 0) {
                            captured.write(buf, 0, Math.min(n, room));
                        }
                        // Past the cap we keep READING so the child never blocks,
                        // we just stop keeping it.
                    }
                }
            } catch (IOException ignored) {
                // stream closed
            }
        });

        try {
            boolean finished = p.waitFor(timeoutSecs, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                drainer.join(1000);
                return new ProcResult(-1, text(captured) + "\n[timed out after " + timeoutSecs + "s]", true);
            }
            drainer.join(2000);
            return new ProcResult(p.exitValue(), text(captured), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return new ProcResult(-1, "interrupted", true);
        }
    }

    private String text(ByteArrayOutputStream captured) {
        synchronized (captured) {
            return captured.toString(StandardCharsets.UTF_8);
        }
    }
}
