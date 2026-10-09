package com.forgeflow.account;

import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileAndAuthTest extends ApiTestSupport {

    @Test
    void meReturnsTheCallerAndNothingSecret() {
        Account a = signup("profile");
        JsonNode me = get("/api/v1/me", a, 200);

        assertThat(me.path("id").asLong()).isEqualTo(a.id());
        assertThat(me.path("email").asText()).isEqualTo(a.email());
        assertThat(me.path("provider").asText()).isEqualTo("local");
        assertThat(me.has("passwordHash")).isFalse();
        assertThat(me.has("password_hash")).isFalse();
        assertThat(me.has("stripeCustomerId")).isFalse();
    }

    @Test
    void patchChangesOnlyWhatIsSent() {
        Account a = signup("patcher");
        call(HttpMethod.PATCH, "/api/v1/me", a, Map.of("avatarUrl", "https://img.example/a.png"), 200);
        JsonNode me = call(HttpMethod.PATCH, "/api/v1/me", a, Map.of("name", "New Name"), 200);

        assertThat(me.path("name").asText()).isEqualTo("New Name");
        assertThat(me.path("avatarUrl").asText()).isEqualTo("https://img.example/a.png");   // untouched
    }

    @Test
    void avatarMustBeHttps() {
        Account a = signup("avatar");
        call(HttpMethod.PATCH, "/api/v1/me", a, Map.of("avatarUrl", "http://img.example/a.png"), 400);
        call(HttpMethod.PATCH, "/api/v1/me", a, Map.of("avatarUrl", "javascript:alert(1)"), 400);
    }

    @Test
    void emailsAreOneAccountRegardlessOfCase() {
        Account a = signup("Case.Sensitive");

        // Logging in with a different case works...
        post("/api/v1/auth/login", null, Map.of("email", a.email().toUpperCase(), "password", "correct-horse-1"), 200);
        // ...and registering the same inbox again in a different case does not.
        post("/api/v1/auth/signup", null,
                Map.of("email", a.email().toUpperCase(), "password", "another-pass-1", "name", "dup"), 409);
    }

    @Test
    void wrongPasswordAndUnknownEmailLookIdentical() {
        Account a = signup("login");
        JsonNode wrongPassword = post("/api/v1/auth/login", null,
                Map.of("email", a.email(), "password", "not-the-password"), 401);
        JsonNode noSuchUser = post("/api/v1/auth/login", null,
                Map.of("email", "ghost-" + System.nanoTime() + "@x.dev", "password", "whatever-1"), 401);
        assertThat(wrongPassword.path("detail").asText()).isEqualTo(noSuchUser.path("detail").asText());
    }
}
