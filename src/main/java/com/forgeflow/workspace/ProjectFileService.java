package com.forgeflow.workspace;

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
 */
@Service
public class ProjectFileService {

    private final ProjectFileRepository files;

    public ProjectFileService(ProjectFileRepository files) {
        this.files = files;
    }

    /** Every file in the project, path -> content, in path order. */
    @Transactional(readOnly = true)
    public Map<String, String> snapshot(Long projectId) {
        Map<String, String> out = new LinkedHashMap<>();
        for (ProjectFile f : files.findByProjectIdOrderByPath(projectId)) {
            out.put(f.getPath(), f.getContent());
        }
        return out;
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
        return files.findByProjectIdAndPath(projectId, path).map(ProjectFile::getContent);
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
        file.setContent(content);
        file.setSizeBytes(content.getBytes(StandardCharsets.UTF_8).length);
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
                zip.write(f.getContent().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return all.size();
    }
}
