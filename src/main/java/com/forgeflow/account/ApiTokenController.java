package com.forgeflow.account;

import com.forgeflow.account.dto.ApiTokenResponse;
import com.forgeflow.account.dto.CreateTokenRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Personal access tokens. Managing them needs a real sign-in: a token can be
 * used for everything else, but not to mint more tokens or to revoke the
 * one that would stop it - so a leaked token can't entrench itself.
 */
@RestController
@RequestMapping("/api/v1/me/tokens")
public class ApiTokenController {

    private final ApiTokenService tokens;

    public ApiTokenController(ApiTokenService tokens) {
        this.tokens = tokens;
    }

    @GetMapping
    public List<ApiTokenResponse> list(Authentication auth) {
        return tokens.list((Long) auth.getPrincipal());
    }

    @PostMapping
    public ResponseEntity<ApiTokenResponse> create(@Valid @RequestBody CreateTokenRequest req, Authentication auth) {
        ApiTokenService.requireSession(auth.getDetails());
        return ResponseEntity.status(HttpStatus.CREATED).body(tokens.create((Long) auth.getPrincipal(), req.name()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable Long id, Authentication auth) {
        ApiTokenService.requireSession(auth.getDetails());
        tokens.revoke((Long) auth.getPrincipal(), id);
        return ResponseEntity.noContent().build();
    }
}
