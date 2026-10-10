package com.forgeflow.shared.security;

import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Sliding sessions: active users stay signed in, idle tabs and ancient sessions don't. */
class SessionRenewalTest extends ApiTestSupport {

    @Value("${forgeflow.jwt.secret}")
    String secret;

    private static final Instant NOW = Instant.parse("2026-10-10T08:00:00Z");

    private JwtService at(Instant when) {
        return new JwtService(secret, 120, 30, Clock.fixed(when, ZoneOffset.UTC));
    }

    @Test
    void aFreshTokenIsLeftAlone() {
        String t = at(NOW).generateToken(7L, "a@b.dev");
        assertThat(at(NOW.plus(Duration.ofMinutes(59))).renewIfAged(t)).isEmpty();
    }

    @Test
    void pastHalfwayItIsRenewedForTheSamePerson() {
        String t = at(NOW).generateToken(7L, "a@b.dev");
        JwtService later = at(NOW.plus(Duration.ofMinutes(61)));

        String fresh = later.renewIfAged(t).orElseThrow();

        assertThat(later.extractUserId(fresh)).isEqualTo(7L);
        // The renewed token is good for a full term from now: still valid 2 hours after the
        // ORIGINAL would have expired... minus a minute.
        assertThat(at(NOW.plus(Duration.ofMinutes(61 + 119))).extractUserId(fresh)).isEqualTo(7L);
    }

    @Test
    void anIdleTokenStillExpires() {
        String t = at(NOW).generateToken(7L, "a@b.dev");
        assertThatThrownBy(() -> at(NOW.plus(Duration.ofMinutes(121))).renewIfAged(t))
                .hasMessageContaining("expired");
    }

    @Test
    void renewalStopsThirtyDaysAfterTheRealSignIn() {
        // Someone keeps the session alive by using it every 70 minutes...
        String current = at(NOW).generateToken(7L, "a@b.dev");
        Instant when = NOW;
        while (current != null && when.isBefore(NOW.plus(Duration.ofDays(31)))) {
            when = when.plus(Duration.ofMinutes(70));
            current = at(when).renewIfAged(current).orElse(null);
        }
        // ...and it slides for 30 days - the original sign-in time travels with
        // every renewal - then stops, and the person has to sign in again.
        assertThat(current).as("renewal must stop").isNull();
        assertThat(when).isBetween(NOW.plus(Duration.ofDays(30)), NOW.plus(Duration.ofDays(30)).plus(Duration.ofMinutes(70)));
    }

    @Test
    void theRenewedTokenComesBackInAHeader() throws Exception {
        Account a = signup("slider");

        String aged = new JwtService(secret, 120, 30,
                Clock.fixed(Instant.now().minus(Duration.ofMinutes(70)), ZoneOffset.UTC))
                .generateToken(a.id(), a.email());

        MockHttpServletResponse r = mvc.perform(MockMvcRequestBuilders.get("/api/v1/me")
                .header("Authorization", "Bearer " + aged)).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(200);
        String fresh = r.getHeader(JwtAuthFilter.RENEWED_TOKEN_HEADER);
        assertThat(fresh).isNotBlank().isNotEqualTo(aged);

        // The new token works, and being fresh, isn't renewed again.
        MockHttpServletResponse again = mvc.perform(MockMvcRequestBuilders.get("/api/v1/me")
                .header("Authorization", "Bearer " + fresh)).andReturn().getResponse();
        assertThat(again.getStatus()).isEqualTo(200);
        assertThat(again.getHeader(JwtAuthFilter.RENEWED_TOKEN_HEADER)).isNull();
    }
}
