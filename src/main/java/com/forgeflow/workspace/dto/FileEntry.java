package com.forgeflow.workspace.dto;

import java.time.Instant;

/** One row of the file tree: everything about a file except its content. */
public record FileEntry(String path, int sizeBytes, int version,
                        Long createdBy, Long updatedBy, Instant updatedAt) {
}
