package com.forgeflow.shared.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
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

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
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
                // FIRST, deliberately. Tomcat re-dispatches errors to /error, and
                // that dispatch arrives anonymous - without this, a real 500 comes
                // back as an empty 403 and you debug the wrong thing for two days.
                .requestMatchers("/error").permitAll()
                // The workbench UI. Static files carry no data of their own; every
                // call they make still needs a token.
                .requestMatchers("/", "/index.html", "/styles.css", "/app.js", "/favicon.ico").permitAll()
                // Preview links are meant to be shareable. The token in the URL is the
                // credential, and PreviewContentController checks it is live.
                .requestMatchers("/p/**").permitAll()
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers("/api/v1/auth/**").permitAll()
                .anyRequest().authenticated())
            // Without this, Spring falls back to Http403ForbiddenEntryPoint and an
            // anonymous caller gets 403 ("not permitted") instead of 401 ("who are
            // you"). We have no httpBasic or formLogin, so nothing else supplies one.
            .exceptionHandling(ex -> ex.authenticationEntryPoint(
                (request, response, authException) ->
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
