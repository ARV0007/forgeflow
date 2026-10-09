package com.forgeflow.account;

import com.forgeflow.account.dto.AuthResponse;
import com.forgeflow.account.dto.LoginRequest;
import com.forgeflow.account.dto.SignupRequest;
import com.forgeflow.shared.security.JwtService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

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
        // Stored normalised, compared case-insensitively. "Aman@x.com" and
        // "aman@x.com" are one inbox, so they must be one account - otherwise a
        // project invite can land on the wrong one.
        String email = normalise(req.email());
        if (users.existsByEmailIgnoreCase(email)) {
            throw new IllegalStateException("Email already registered");
        }
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(encoder.encode(req.password()));
        user.setName(req.name());
        user.setProvider(User.LOCAL_PROVIDER);

        User saved = users.save(user);
        return new AuthResponse(jwt.generateToken(saved.getId(), saved.getEmail()),
                saved.getId(), saved.getEmail());
    }

    public AuthResponse login(LoginRequest req) {
        // Identical message on EVERY failure path, so a caller cannot use the
        // response to learn which emails exist, which are Google accounts, or
        // which were deleted.
        User user = users.findByEmailIgnoreCaseAndDeletedAtIsNull(normalise(req.email()))
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        // An account created through an identity provider has no password to
        // match against. encoder.matches(x, null) would throw, not return false.
        if (user.getPasswordHash() == null || !encoder.matches(req.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        return new AuthResponse(jwt.generateToken(user.getId(), user.getEmail()),
                user.getId(), user.getEmail());
    }

    static String normalise(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
