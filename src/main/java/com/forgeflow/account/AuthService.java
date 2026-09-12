package com.forgeflow.account;

import com.forgeflow.account.dto.AuthResponse;
import com.forgeflow.account.dto.LoginRequest;
import com.forgeflow.account.dto.SignupRequest;
import com.forgeflow.shared.security.JwtService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    @Transactional
    public AuthResponse signup(SignupRequest req) {
        if (users.existsByEmail(req.email())) {
            throw new IllegalStateException("Email already registered");
        }
        User user = new User();
        user.setEmail(req.email());
        user.setPasswordHash(encoder.encode(req.password()));
        user.setName(req.name());

        User saved = users.save(user);
        return new AuthResponse(jwt.generateToken(saved.getId(), saved.getEmail()),
                saved.getId(), saved.getEmail());
    }

    public AuthResponse login(LoginRequest req) {
        // Identical message on BOTH failure paths, so a caller cannot use the
        // response to discover which emails are registered.
        User user = users.findByEmail(req.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!encoder.matches(req.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        return new AuthResponse(jwt.generateToken(user.getId(), user.getEmail()),
                user.getId(), user.getEmail());
    }
}
