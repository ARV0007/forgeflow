package com.forgeflow.account.dto;

/** No password hash, ever. The DTO is the contract; the entity is not. */
public record AuthResponse(String token, Long userId, String email) {
}
