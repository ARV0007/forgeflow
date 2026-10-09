package com.forgeflow.shared;

/**
 * 403: the caller can see this thing, but may not do this to it.
 *
 * Contrast ResourceNotFoundException (404), which is also what a caller gets
 * for something that exists but that they have no relationship to at all. The
 * split is deliberate: a VIEWER already knows the project exists, so telling
 * them "not allowed" leaks nothing; a stranger must not learn even that much.
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
