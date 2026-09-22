package com.forgeflow.execution;

import java.util.Map;

/**
 * Where generated code gets checked and served.
 *
 * An interface because the security model differs per backend. v1 is Docker on
 * one machine; Phase 2 is Kubernetes with a namespace per project. Swapping one
 * for the other should change which class Spring injects, not the callers.
 */
public interface SandboxProvider {

    /** Verify the project in a locked-down, network-less container. */
    BuildResult build(Long projectId, Map<String, String> files);

    /** Serve the project and return a URL. Replaces any running preview. */
    PreviewHandle startPreview(Long projectId, Map<String, String> files);

    void stopPreview(Long projectId);
}
