package com.forgeflow.shared.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import com.forgeflow.shared.ratelimit.RateLimitFilter;
import com.forgeflow.shared.ratelimit.RateLimitRules;
import com.forgeflow.shared.ratelimit.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final RateLimiter rateLimiter;
    private final RateLimitRules rateLimitRules;
    private final boolean rateLimitEnabled;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, RateLimiter rateLimiter, RateLimitRules rateLimitRules,
                          @Value("${forgeflow.ratelimit.enabled:true}") boolean rateLimitEnabled) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.rateLimiter = rateLimiter;
        this.rateLimitRules = rateLimitRules;
        this.rateLimitEnabled = rateLimitEnabled;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // No browser sessions, no forms - nothing for CSRF to protect.
                .csrf(csrf -> csrf.disable())
                // X-Frame-Options: DENY is Spring's default and it is right for the
                // app itself. But previews are now served by this same application,
                // so the workbench cannot frame its own /p/ pages. SAMEORIGIN keeps
                // other sites out while letting our iframe work. The generated code
                // is still contained: every preview response carries a CSP sandbox
                // directive, which is the control that actually matters here.
                .headers(h -> h.frameOptions(f -> f.sameOrigin()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // An SSE response ends with an ASYNC re-dispatch of the SAME
                        // request back through every filter. The JWT filter runs once
                        // per request, so on that pass nobody is authenticated, and
                        // without this line security threw AccessDenied onto a response
                        // already streaming - the browser saw the stream break after a
                        // successful run. The request was authorised on its way in;
                        // this is its way out. (RealServerStreamTest.)
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        // FIRST, deliberately. Tomcat re-dispatches errors to /error, and
                        // that dispatch arrives anonymous - without this, a real 500 comes
                        // back as an empty 403 and you debug the wrong thing for two days.
                        .requestMatchers("/error").permitAll()
                        // The workbench UI. Static files carry no data of their own; every
                        // call they make still needs a token.
                        .requestMatchers("/", "/index.html", "/styles.css", "/app.js", "/favicon.ico").permitAll()
                        // "Run with Node": the WebContainer page and its client library.
                        .requestMatchers("/run.html", "/run.js", "/vendor/**").permitAll()
                        // The API reference: the spec and the page that renders it.
                        .requestMatchers("/openapi.yaml", "/docs.html", "/docs").permitAll()
                        // Preview links are meant to be shareable. The token in the URL is the
                        // credential, and PreviewContentController checks it is live.
                        .requestMatchers("/p/**").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // The pricing page is public. Stripe's webhook carries no JWT - its
                        // HMAC signature is the credential, checked in BillingController.
                        .requestMatchers(HttpMethod.GET, "/api/v1/billing/plans").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/billing/webhook/**").permitAll()
                        // Test-mode checkout page; the controller only exists when billing=fake,
                        // and the unguessable session id in the path is the credential.
                        .requestMatchers("/billing/fake-checkout/**").permitAll()
                        // MCP clients arrive without a JWT; the tool layer runs as a fixed
                        // service account. See McpController.
                        .requestMatchers("/mcp").permitAll()
                        .anyRequest().authenticated())
                // Without this, Spring falls back to Http403ForbiddenEntryPoint and an
                // anonymous caller gets 403 ("not permitted") instead of 401 ("who are
                // you"). We have no httpBasic or formLogin, so nothing else supplies one.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        (request, response, authException) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        // Right after the JWT filter: late enough to know who is calling, early
        // enough that a limited request never reaches a controller.
        if (rateLimitEnabled) {
            http.addFilterAfter(new RateLimitFilter(rateLimiter, rateLimitRules), JwtAuthFilter.class);
        }

        return http.build();
    }
}