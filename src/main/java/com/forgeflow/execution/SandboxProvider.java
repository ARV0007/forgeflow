package com.forgeflow.execution;

import java.util.Map;

/**
 * Where generated code gets checked and served.
 *
 * An interface because the security model differs per backend: in-process
 * (Render: structure checks, files served by the API), Docker (one machine:
 * locked-down build container, nginx per preview), Kubernetes (a namespace and
 * a pod per preview). Swapping one for another changes which class Spring
 * injects, not the callers.
 */
public interface SandboxProvider {

    /** Verify the project in a locked-down, network-less container. */
    BuildResult build(Long projectId, Map<String, String> files);

    /** Serve the project and return a URL. Replaces any running preview. */
    PreviewHandle startPreview(Long projectId, Map<String, String> files);

    void stopPreview(Long projectId);

    /**
     * Whether a running preview serves a COPY of the files (a container, a
     * pod) rather than reading the project live. If so, code.generated has to
     * push changes into it - see CodeChangeNotifier.
     */
    default boolean previewsCopyFiles() {
        return false;
    }

    /** Put the current files into the running preview, keeping its address. */
    default void refreshPreview(Long projectId, Map<String, String> files) {
    }
}
