package com.forgeflow.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Integration tests run the real application - security filter chain, Flyway
 * schema, real Postgres - and talk to it over MockMvc exactly as a client would.
 *
 * Every account gets a random email, so tests never collide with each other or
 * with data a previous run left behind. No test relies on a clean database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class ApiTestSupport {

    @Autowired
    protected MockMvc mvc;

    protected final ObjectMapper json = new ObjectMapper();

    public record Account(Long id, String email, String token) {
    }

    protected Account signup(String label) {
        String email = label + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.forgeflow.dev";
        JsonNode body = call(HttpMethod.POST, "/api/v1/auth/signup", null,
                Map.of("email", email, "password", "correct-horse-1", "name", label), 201);
        return new Account(body.path("userId").asLong(), email, body.path("token").asText());
    }

    protected long createProject(Account owner, String name) {
        return call(HttpMethod.POST, "/api/v1/projects", owner,
                Map.of("name", name, "description", "test"), 201).path("id").asLong();
    }

    /** Call an endpoint, assert the status, and return the parsed body (or null if empty). */
    protected JsonNode call(HttpMethod method, String path, Account as, Object body, int expectedStatus) {
        try {
            MockHttpServletRequestBuilder req = request(method, path);
            if (as != null) {
                req.header("Authorization", "Bearer " + as.token());
            }
            if (body != null) {
                req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
            }
            MvcResult result = mvc.perform(req).andReturn();
            String text = result.getResponse().getContentAsString();
            assertThat(result.getResponse().getStatus())
                    .as("%s %s -> %s", method, path, text)
                    .isEqualTo(expectedStatus);
            return text.isBlank() ? null : json.readTree(text);
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected JsonNode get(String path, Account as, int status) {
        return call(HttpMethod.GET, path, as, null, status);
    }

    protected JsonNode post(String path, Account as, Object body, int status) {
        return call(HttpMethod.POST, path, as, body, status);
    }
}
