package com.forgeflow.shared.security;

import java.util.Optional;

/**
 * Personal access tokens, as the security filter sees them. Implemented in
 * the account module (which owns the table); declared here so the filter in
 * shared can use it without depending on account.
 */
public interface AccessTokens {

    String PREFIX = "ff_pat_";

    /** The user a live (not revoked) token belongs to. */
    Optional<Long> userFor(String token);
}
