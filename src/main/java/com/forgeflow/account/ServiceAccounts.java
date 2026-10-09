package com.forgeflow.account;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Non-human accounts - today, the identity MCP callers act as.
 *
 * Previously MCP used a hard-coded user id (1). That was a landmine: on a real
 * deployment user 1 is whoever signed up first, so every MCP-created project
 * landed in a real person's account; on a fresh database user 1 did not exist
 * at all and every MCP call failed a foreign key.
 *
 * A service account is found by email and created on first use. It has no
 * password and provider "service", so AuthService.login can never succeed for
 * it - it cannot be signed into, only acted as from inside the server.
 */
@Service
public class ServiceAccounts {

    public static final String SERVICE_PROVIDER = "service";

    private final UserRepository users;

    public ServiceAccounts(UserRepository users) {
        this.users = users;
    }

    @Transactional
    public Long ensure(String email, String displayName) {
        String normalised = AuthService.normalise(email);
        return users.findByEmailIgnoreCaseAndDeletedAtIsNull(normalised)
                .map(User::getId)
                .orElseGet(() -> create(normalised, displayName));
    }

    private Long create(String email, String displayName) {
        User u = new User();
        u.setEmail(email);
        u.setName(displayName);
        u.setProvider(SERVICE_PROVIDER);
        u.setPasswordHash(null);        // no password: never loginable
        u.setEmailVerified(true);
        try {
            return users.saveAndFlush(u).getId();
        } catch (DataIntegrityViolationException raced) {
            // Two first-calls at once: the other one won. Use theirs.
            return users.findByEmailIgnoreCaseAndDeletedAtIsNull(email).orElseThrow().getId();
        }
    }
}
