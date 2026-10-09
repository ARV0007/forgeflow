package com.forgeflow.account.dto;

/**
 * What other modules are allowed to know about a person.
 *
 * Deliberately narrow: no password hash, no provider ids, no billing ids.
 * workspace needs a name and an email to show a member list, and nothing
 * else, so nothing else crosses the module boundary.
 */
public record UserSummary(Long id, String email, String name, String avatarUrl) {
}
