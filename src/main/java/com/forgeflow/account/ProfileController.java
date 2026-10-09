package com.forgeflow.account;

import com.forgeflow.account.dto.ProfileResponse;
import com.forgeflow.account.dto.UpdateProfileRequest;
import com.forgeflow.shared.ResourceNotFoundException;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Spec: "Get My Profile". The caller is always the JWT's subject - never a path id. */
@RestController
@RequestMapping("/api/v1/me")
public class ProfileController {

    private final UserRepository users;

    public ProfileController(UserRepository users) {
        this.users = users;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ProfileResponse me(Authentication auth) {
        return toResponse(load(auth));
    }

    @PatchMapping
    @Transactional
    public ProfileResponse update(@Valid @RequestBody UpdateProfileRequest req, Authentication auth) {
        User user = load(auth);
        if (req.name() != null) {
            user.setName(req.name().isBlank() ? null : req.name().trim());
        }
        if (req.avatarUrl() != null) {
            user.setAvatarUrl(req.avatarUrl());
        }
        return toResponse(users.save(user));
    }

    /**
     * A JWT outlives the account it was minted for - verification is local and
     * never asks the database. So a deleted account's token still parses; this
     * is where it stops working.
     */
    private User load(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return users.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
    }

    private static ProfileResponse toResponse(User u) {
        return new ProfileResponse(u.getId(), u.getEmail(), u.getName(), u.getAvatarUrl(),
                u.getProvider(), u.isEmailVerified(), u.getCreatedAt());
    }
}
