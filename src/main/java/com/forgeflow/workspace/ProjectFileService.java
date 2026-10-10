package com.forgeflow.workspace;

import com.forgeflow.shared.storage.ObjectStore;
import com.forgeflow.shared.storage.SigV4;
import com.forgeflow.workspace.dto.FileEntry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The workspace module's public face for file contents - the only class in the
 * application that reads or writes project_files.
 *
 * Other modules call THIS, not ProjectFileRepository. That is the module
 * boundary rule from architecture.md section 3: it is what keeps a future move
 * of content into object storage (the spec's MinIO) a change to one class, and
 * a split into separate services a config change rather than a rewrite.
 *
 * No access checks here. Callers check first (ProjectService.getById /
 * requireWrite); this class trusts the project id it is handed.
 *
 * Where the bytes live is configuration (forgeflow.storage.files):
 *
 *   postgres  the content column (default; Render, tests)
 *   s3        an object in a bucket - MinIO in the full topology - under a
 *             content-addressed key, projects/<id>/blobs/<sha256>. The row
 *             keeps the metadata (path, size, version, authors) and the key.
 *
 * Content-addressed keys make writes idempotent (the same bytes, the same
 * key), store identical files once, and never overwrite an object - so a key
 * recorded anywhere stays readable. The cost is garbage: replaced blobs stay
 * in the bucket until a sweep removes unreferenced ones (not built).
 *
 * The object is written before the row commits. If the commit then fails,
 * the bucket holds an orphan blob - harmless. The other order could leave a
 * row pointing at nothing.
 */
@Service
public class ProjectFileService {

    private final ProjectFileRepository files;
    private final ObjectStore blobs;          // null: contents live in Postgres

    public ProjectFileService(ProjectFileRepository files, Optional<ObjectStore> blobs) {
        this.files = files;
        this.blobs = blobs.orElse(null);
    }

    /** "postgres" or the object store's description - for logs and the health page. */
    public String storageDescription() {
        return blobs == null ? "postgres" : blobs.describe();
    }

    private String contentOf(ProjectFile f) {
        if (f.getContent() != null) {
            return f.getContent();
        }
        if (blobs == null) {
            throw new IllegalStateException("File " + f.getPath() + " of project " + f.getProjectId()
                    + " is in object storage, but forgeflow.storage.files is not s3");
        }
        return blobs.get(f.getObjectKey())
                .map(b -> new String(b, StandardCharsets.UTF_8))
                .orElseThrow(() -> new IllegalStateException("Object " + f.getObjectKey() + " is missing"));
    }

    static String blobKey(Long projectId, byte[] content) {
        return "projects/" + projectId + "/blobs/" + SigV4.sha256Hex(content);
    }

    /** Every file in the project, path -> content, in path order. */
    @Transactional(readOnly = true)
    public Map<String, String> snapshot(Long projectId) {
        Map<String, String> out = new LinkedHashMap<>();
        for (ProjectFile f : files.findByProjectIdOrderByPath(projectId)) {
            out.put(f.getPath(), contentOf(f));
        }
        return out;
    }

    /**
     * Where each file's bytes are and their fingerprint, without fetching
     * object contents: in s3 mode the key already ends in the SHA-256. What
     * version history snapshots.
     */
    public record FileRef(String path, String sha256, int sizeBytes, String content, String objectKey) {
    }

    @Transactional(readOnly = true)
    public List<FileRef> refs(Long projectId) {
        return files.findByProjectIdOrderByPath(projectId).stream().map(f -> {
            if (f.getObjectKey() != null) {
                String key = f.getObjectKey();
                return new FileRef(f.getPath(), key.substring(key.lastIndexOf('/') + 1), f.getSizeBytes(), null, key);
            }
            return new FileRef(f.getPath(), SigV4.sha256Hex(f.getContent().getBytes(StandardCharsets.UTF_8)),
                    f.getSizeBytes(), f.getContent(), null);
        }).toList();
    }

    /** The text of a stored blob: inline, or fetched from object storage. */
    String blobText(String content, String objectKey) {
        if (content != null) {
            return content;
        }
        if (blobs == null) {
            throw new IllegalStateException("Blob " + objectKey + " is in object storage, but forgeflow.storage.files is not s3");
        }
        return blobs.get(objectKey).map(b -> new String(b, StandardCharsets.UTF_8))
                .orElseThrow(() -> new IllegalStateException("Object " + objectKey + " is missing"));
    }

    /** Remove a file. Its blob stays (history may point at it). @return whether it existed */
    @Transactional
    public boolean delete(Long projectId, String path) {
        return files.findByProjectIdAndPath(projectId, path).map(f -> {
            files.delete(f);
            return true;
        }).orElse(false);
    }

    /** Spec: "File Tree". Metadata only - no content. */
    @Transactional(readOnly = true)
    public List<FileEntry> list(Long projectId) {
        return files.findByProjectIdOrderByPath(projectId).stream()
                .map(f -> new FileEntry(f.getPath(), f.getSizeBytes(), f.getVersion(),
                        f.getCreatedBy(), f.getUpdatedBy(), f.getUpdatedAt()))
                .toList();
    }

    /** Spec: "File Content". */
    @Transactional(readOnly = true)
    public Optional<String> read(Long projectId, String path) {
        return files.findByProjectIdAndPath(projectId, path).map(this::contentOf);
    }

    /**
     * Create or overwrite one file, recording who did it.
     *
     * The path is expected to be clean already - the agent's write tool owns the
     * path guard, because it is the code receiving model-written paths. The
     * check here is a backstop, not the defence.
     *
     * @return the file's version after this write (1 for a new file)
     */
    @Transactional
    public int write(Long projectId, String path, String content, Long authorId) {
        if (path == null || path.isBlank() || path.contains("..") || path.startsWith("/")) {
            throw new IllegalArgumentException("Refusing an unsafe file path: " + path);
        }
        ProjectFile file = files.findByProjectIdAndPath(projectId, path).orElseGet(() -> {
            ProjectFile f = new ProjectFile();
            f.setProjectId(projectId);
            f.setPath(path);
            f.setVersion(0);
            f.setCreatedBy(authorId);
            return f;
        });
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (blobs == null) {
            file.setContent(content);
            file.setObjectKey(null);
        } else {
            String key = blobKey(projectId, bytes);
            blobs.put(key, bytes, "text/plain; charset=utf-8");
            file.setObjectKey(key);
            file.setContent(null);
        }
        file.setSizeBytes(bytes.length);
        file.setVersion(file.getVersion() + 1);
        file.setUpdatedBy(authorId);
        return files.save(file).getVersion();
    }

    /**
     * Spec: "Download all files as zip". Writes every file into a zip, under one
     * top-level folder so unzipping does not spray files into the user's
     * Downloads directory.
     *
     * @return how many files went in
     */
    @Transactional(readOnly = true)
    public int writeZip(Long projectId, String rootFolder, OutputStream out) throws IOException {
        List<ProjectFile> all = files.findByProjectIdOrderByPath(projectId);
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (ProjectFile f : all) {
                ZipEntry entry = new ZipEntry(rootFolder + "/" + f.getPath());
                if (f.getUpdatedAt() != null) {
                    entry.setLastModifiedTime(FileTime.from(f.getUpdatedAt()));
                }
                zip.putNextEntry(entry);
                zip.write(contentOf(f).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return all.size();
    }
}
