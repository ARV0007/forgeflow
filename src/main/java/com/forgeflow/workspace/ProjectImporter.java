package com.forgeflow.workspace;

import com.forgeflow.shared.events.CodeGenerated;
import com.forgeflow.shared.events.EventBus;
import com.forgeflow.shared.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;

/**
 * Bring an existing codebase into a project: a zip upload, or a public GitHub
 * repo (downloaded as GitHub's own zip of it).
 *
 * This is what makes RAG necessary instead of idle: a generated app is three
 * files the agent can read whole, an imported repo is hundreds it can't.
 *
 * Almost all of the work is deciding what NOT to take in:
 *   - folders nobody edits by hand (node_modules, .git, dist, build...)
 *   - binaries: images, fonts, archives - by extension, then by content (a NUL
 *     byte, or bytes that aren't valid UTF-8)
 *   - files over the 200 KB file cap every other write has
 *   - hostile paths: absolute, "..", backslashes (zip slip)
 *   - zip bombs: the sizes a zip claims are ignored; the bytes actually
 *     inflated are counted, and reading stops at the limit
 *
 * An import replaces the project's files. Nothing is lost: what was there is
 * checkpointed first, and the import is a version of its own in History.
 */
@Service
public class ProjectImporter {

    private static final Logger log = LoggerFactory.getLogger(ProjectImporter.class);

    static final int MAX_FILE_BYTES = 200_000;
    static final int MAX_ENTRIES = 20_000;
    static final Set<String> IGNORED_DIRS = Set.of(
            "node_modules", ".git", "dist", "build", "out", ".next", ".nuxt", ".svelte-kit", "coverage",
            "vendor", "target", "__pycache__", ".venv", "venv", ".idea", ".vscode", ".gradle", ".cache");
    static final Set<String> IGNORED_FILES = Set.of(".DS_Store", "Thumbs.db");
    static final Set<String> BINARY_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "tiff", "avif", "psd",
            "woff", "woff2", "ttf", "otf", "eot",
            "zip", "gz", "tgz", "tar", "rar", "7z", "jar", "war", "class",
            "mp3", "mp4", "mov", "wav", "ogg", "webm", "avi",
            "pdf", "exe", "dll", "so", "dylib", "bin", "wasm", "o", "a", "pyc");

    /** owner/repo, as GitHub allows them; anything else never reaches the URL. */
    private static final Pattern GITHUB = Pattern.compile(
            "^(?:https?://)?(?:www\\.)?github\\.com/([A-Za-z0-9-]{1,39})/([A-Za-z0-9._-]{1,100}?)(?:\\.git)?"
                    + "(?:/tree/([A-Za-z0-9._/-]{1,200}))?/?$");

    public enum Skip { IGNORED_FOLDER, BINARY, TOO_LARGE, UNSAFE_PATH, OVER_FILE_LIMIT }

    /** What happened, for the person who clicked Import. */
    public record Report(String source, int imported, int removed, Map<Skip, Integer> skipped, long bytes,
                         Long checkpointId) {
    }

    /** A request we refuse before touching the project: not a zip, too big, bad URL, repo not found. */
    public static class ImportRefused extends com.forgeflow.shared.BadRequestException {
        public ImportRefused(String message) {
            super(message);
        }
    }

    private final ProjectFileService files;
    private final CheckpointService checkpoints;
    private final EventBus events;
    private final HttpClient http;
    private final String githubBase;
    private final long maxZipBytes;
    private final long maxTotalBytes;
    private final int maxFiles;

    public ProjectImporter(ProjectFileService files, CheckpointService checkpoints, EventBus events,
                           @Value("${forgeflow.import.github-base:https://github.com}") String githubBase,
                           @Value("${forgeflow.import.max-zip-mb:20}") long maxZipMb,
                           @Value("${forgeflow.import.max-total-mb:30}") long maxTotalMb,
                           @Value("${forgeflow.import.max-files:1500}") int maxFiles) {
        this.files = files;
        this.checkpoints = checkpoints;
        this.events = events;
        this.githubBase = githubBase.replaceAll("/+$", "");
        this.maxZipBytes = maxZipMb * 1024 * 1024;
        this.maxTotalBytes = maxTotalMb * 1024 * 1024;
        this.maxFiles = maxFiles;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)   // github.com -> codeload.github.com
                .build();
    }

    // ---------------------------------------------------------------- entry points

    /** Access (WRITE) is checked by the controller. */
    public Report importZip(Long projectId, Long userId, InputStream upload, String label) {
        byte[] zip = readCapped(upload, maxZipBytes, "The zip is larger than " + (maxZipBytes >> 20) + " MB.");
        return apply(projectId, userId, unzip(zip, null), label);
    }

    public Report importGithub(Long projectId, Long userId, String url) {
        Matcher m = GITHUB.matcher(url == null ? "" : url.strip());
        if (!m.matches()) {
            throw new ImportRefused("That doesn't look like a GitHub repo address - expected https://github.com/owner/repo");
        }
        String owner = m.group(1);
        String repo = m.group(2);
        String ref = "HEAD";
        String subdir = null;
        if (m.group(3) != null) {
            // /tree/<branch>[/<folder>]: the first segment is the branch (a
            // branch with a slash in its name can't be told apart; first wins).
            String[] parts = m.group(3).split("/", 2);
            ref = parts[0];
            subdir = parts.length > 1 ? parts[1].replaceAll("/+$", "") : null;
            if (ref.contains("..") || (subdir != null && subdir.contains(".."))) {
                throw new ImportRefused("That branch or folder name isn't allowed.");
            }
        }
        byte[] zip = download(githubBase + "/" + owner + "/" + repo + "/archive/" + ref + ".zip");
        String label = "Imported from github.com/" + owner + "/" + repo + (subdir != null ? "/" + subdir : "")
                + ("HEAD".equals(ref) ? "" : " @ " + ref);
        return apply(projectId, userId, unzip(zip, subdir), label);
    }

    // ---------------------------------------------------------------- the work

    record Unzipped(Map<String, String> files, Map<Skip, Integer> skipped, long bytes) {
    }

    /**
     * Read with ZipFile, which jumps to each entry through the zip's table of
     * contents. ZipInputStream would have to inflate every entry fully just to
     * reach the next one - so "skipping" a 10 GB bomb entry would still burn
     * the CPU inflating it. Here an entry is only inflated if it is read, and
     * a read stops one byte past the file cap. Sizes the zip claims are used
     * to skip, never trusted to keep: what is kept is what was actually read.
     * A single top folder (GitHub's "repo-main/") is removed.
     */
    Unzipped unzip(byte[] zip, String subdir) {
        Map<String, byte[]> raw = new LinkedHashMap<>();
        Map<Skip, Integer> skipped = new TreeMap<>();
        long total = 0;
        java.nio.file.Path tmp = null;
        try {
            tmp = java.nio.file.Files.createTempFile("forgeflow-import-", ".zip");
            java.nio.file.Files.write(tmp, zip);
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(tmp.toFile(), StandardCharsets.UTF_8)) {
                if (zf.size() == 0) {
                    throw new ImportRefused("That zip is empty.");
                }
                if (zf.size() > MAX_ENTRIES) {
                    throw new ImportRefused("That zip has more than " + MAX_ENTRIES + " entries - too many to import.");
                }
                var entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (e.isDirectory()) {
                        continue;
                    }
                    String name = e.getName();
                    if (unsafe(name)) {
                        count(skipped, Skip.UNSAFE_PATH);
                        continue;
                    }
                    if (e.getSize() > MAX_FILE_BYTES) {          // claimed too big: skipped without inflating
                        raw.put(name, null);
                        continue;
                    }
                    byte[] data;
                    try (InputStream in = zf.getInputStream(e)) {
                        data = in.readNBytes(MAX_FILE_BYTES + 1);   // a lying header still stops here
                    }
                    total += data.length;
                    if (total > maxTotalBytes) {
                        throw new ImportRefused("The zip unpacks to more than " + (maxTotalBytes >> 20) + " MB - too big to import.");
                    }
                    raw.put(name, data);
                }
            }
        } catch (ZipException ex) {
            throw new ImportRefused("That isn't a readable zip file.");
        } catch (IOException ex) {
            throw new ImportRefused("The zip couldn't be read: " + ex.getMessage());
        } finally {
            if (tmp != null) {
                try {
                    java.nio.file.Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // a temp file the OS clears anyway
                }
            }
        }

        String root = commonRoot(raw.keySet());
        Map<String, String> out = new TreeMap<>();
        long bytes = 0;
        for (Map.Entry<String, byte[]> entry : raw.entrySet()) {
            String path = entry.getKey().substring(root.length());
            if (subdir != null) {
                if (!path.startsWith(subdir + "/")) {
                    continue;
                }
                path = path.substring(subdir.length() + 1);
            }
            byte[] data = entry.getValue();
            Skip why = data == null ? (ignoredFolder(path) ? Skip.IGNORED_FOLDER : Skip.TOO_LARGE) : reject(path, data);
            if (why != null) {
                count(skipped, why);
                continue;
            }
            if (out.size() >= maxFiles) {
                count(skipped, Skip.OVER_FILE_LIMIT);
                continue;
            }
            out.put(path, new String(data, StandardCharsets.UTF_8));
            bytes += data.length;
        }
        if (out.isEmpty()) {
            throw new ImportRefused("Nothing to import: no text files left after skipping folders like node_modules, binaries and large files.");
        }
        return new Unzipped(out, skipped, bytes);
    }

    private Report apply(Long projectId, Long userId, Unzipped u, String label) {
        // What was here stays in History, so the replace can be undone.
        if (!files.list(projectId).isEmpty()) {
            checkpoints.record(projectId, "BASELINE", "Before " + lowerFirst(label), null, userId);
        }
        Map<String, String> current = files.snapshot(projectId);
        List<String> touched = new ArrayList<>();
        int removed = 0;
        for (String path : current.keySet()) {
            if (!u.files().containsKey(path)) {
                files.delete(projectId, path);
                touched.add(path);
                removed++;
            }
        }
        for (Map.Entry<String, String> f : u.files().entrySet()) {
            if (!f.getValue().equals(current.get(f.getKey()))) {
                files.write(projectId, f.getKey(), f.getValue(), userId);
                touched.add(f.getKey());
            }
        }
        Long checkpointId = checkpoints.record(projectId, "IMPORT", label, null, userId)
                .map(CheckpointService.Summary::id).orElse(null);
        if (!touched.isEmpty()) {
            // The indexer re-embeds, an open preview reloads - as after any AI run.
            events.publish(Topics.CODE_GENERATED, String.valueOf(projectId),
                    new CodeGenerated(projectId, null, userId, "IMPORTED", List.copyOf(touched), Instant.now()));
        }
        log.info("project {}: {} - {} file(s) in, {} removed, skipped {}", projectId, label, u.files().size(), removed, u.skipped());
        return new Report(label, u.files().size(), removed, u.skipped(), u.bytes(), checkpointId);
    }

    // ---------------------------------------------------------------- rules

    static boolean unsafe(String name) {
        return name.isBlank() || name.startsWith("/") || name.contains("\\") || name.contains("\0")
                || name.matches("^[A-Za-z]:.*") || List.of(name.split("/")).contains("..");
    }

    static boolean ignoredFolder(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (IGNORED_DIRS.contains(parts[i])) {
                return true;
            }
        }
        return false;
    }

    /** null = keep it. */
    static Skip reject(String path, byte[] data) {
        if (ignoredFolder(path)) {
            return Skip.IGNORED_FOLDER;
        }
        String[] parts = path.split("/");
        String name = parts[parts.length - 1];
        if (name.isEmpty() || unsafe(path)) {
            return Skip.UNSAFE_PATH;
        }
        if (IGNORED_FILES.contains(name)) {
            return Skip.IGNORED_FOLDER;
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0 && BINARY_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) {
            return Skip.BINARY;
        }
        if (data.length > MAX_FILE_BYTES) {
            return Skip.TOO_LARGE;
        }
        if (!isText(data)) {
            return Skip.BINARY;
        }
        return null;
    }

    /** No NUL bytes in the first 8 KB, and valid UTF-8 throughout. */
    static boolean isText(byte[] data) {
        for (int i = 0; i < Math.min(data.length, 8192); i++) {
            if (data[i] == 0) {
                return false;
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /** "repo-main/" when every entry sits under that one folder, else "". */
    static String commonRoot(Set<String> names) {
        String root = null;
        for (String n : names) {
            int slash = n.indexOf('/');
            if (slash < 0) {
                return "";
            }
            String first = n.substring(0, slash + 1);
            if (root == null) {
                root = first;
            } else if (!root.equals(first)) {
                return "";
            }
        }
        return root == null ? "" : root;
    }

    // ---------------------------------------------------------------- io

    private byte[] download(String url) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("User-Agent", "ForgeFlow-import").GET().build();
        try {
            HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = res.body()) {
                if (res.statusCode() == 404) {
                    throw new ImportRefused("GitHub has no public repo (or branch) at that address. Private repos can't be imported yet.");
                }
                if (res.statusCode() != 200) {
                    throw new ImportRefused("GitHub answered " + res.statusCode() + " - try again in a minute.");
                }
                return readCapped(body, maxZipBytes, "That repo's zip is larger than " + (maxZipBytes >> 20) + " MB.");
            }
        } catch (IOException e) {
            throw new ImportRefused("Couldn't reach GitHub: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImportRefused("Interrupted while downloading.");
        }
    }

    private static byte[] readCapped(InputStream in, long cap, String tooBig) {
        try {
            byte[] data = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, cap + 1));
            if (data.length > cap) {
                throw new ImportRefused(tooBig);
            }
            return data;
        } catch (IOException e) {
            throw new ImportRefused("The upload couldn't be read: " + e.getMessage());
        }
    }

    private static void count(Map<Skip, Integer> skipped, Skip why) {
        skipped.merge(why, 1, Integer::sum);
    }

    private static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
