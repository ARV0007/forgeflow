package com.forgeflow.architecture;

import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * openapi.yaml is hand-written - so this test is what stops it drifting.
 *
 * It asks Spring for every route the application actually serves under /api
 * and /mcp, reads every operation the document claims, and requires the two
 * sets to be identical. Add an endpoint without documenting it, or delete
 * one and leave its docs behind, and the build fails.
 */
class OpenApiContractTest extends ApiTestSupport {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");

    @Test
    void everyRouteIsDocumentedAndEveryDocumentedRouteExists() throws Exception {
        Set<String> served = new TreeSet<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/") && !pattern.equals("/mcp")) {
                    continue;
                }
                for (RequestMethod m : info.getMethodsCondition().getMethods()) {
                    served.add(m.name() + " " + normalise(pattern));
                }
            }
        }

        Set<String> documented = new TreeSet<>();
        Map<String, Object> spec;
        try (InputStream in = getClass().getResourceAsStream("/static/openapi.yaml")) {
            spec = new Yaml().load(in);
        }
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) spec.get("paths");
        paths.forEach((path, ops) -> ops.keySet().stream()
                .filter(HTTP_METHODS::contains)
                .forEach(m -> documented.add(m.toUpperCase() + " " + normalise(path))));

        assertThat(documented).as("documented but not served").isSubsetOf(served);
        assertThat(served).as("served but not documented in openapi.yaml").isSubsetOf(documented);
    }

    /** {projectId} and {id} are the same thing to a router; compare shapes, not names. */
    private static String normalise(String path) {
        return path.replaceAll("\\{[^}]+}", "{}");
    }

    @Test
    void theSpecAndItsViewerArePublic() throws Exception {
        MvcResult spec = mvc.perform(MockMvcRequestBuilders.get("/openapi.yaml")).andReturn();
        assertThat(spec.getResponse().getStatus()).isEqualTo(200);
        assertThat(spec.getResponse().getContentAsString()).startsWith("openapi: 3.1.0");

        MvcResult page = mvc.perform(MockMvcRequestBuilders.get("/docs.html")).andReturn();
        assertThat(page.getResponse().getStatus()).isEqualTo(200);
        assertThat(page.getResponse().getContentAsString()).contains("swagger-ui-dist@5.17.14");
    }
}
