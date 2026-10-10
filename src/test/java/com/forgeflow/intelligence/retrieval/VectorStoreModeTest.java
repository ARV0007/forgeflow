package com.forgeflow.intelligence.retrieval;

import com.forgeflow.support.ApiTestSupport;
import com.forgeflow.workspace.ProjectFileService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;

/** Search with nearest-neighbour in Qdrant - and what happens when Qdrant is gone. */
class VectorStoreModeTest {

    abstract static class Base extends ApiTestSupport {
        @Autowired
        ProjectFileService files;
        @Autowired
        VectorIndex vectors;

        long project(Account a) {
            long id = createProject(a, "vectors");
            files.write(id, "theme.js", "// Dark mode toggle\nfunction toggleDarkMode() { document.body.classList.toggle('dark'); }", a.id());
            files.write(id, "cart.js", "function addToCart(item) { cart.push(item); }", a.id());
            return id;
        }

        MvcResult search(long id, Account a, String q) throws Exception {
            return mvc.perform(MockMvcRequestBuilders.get("/api/v1/projects/" + id + "/search")
                    .param("q", q).param("mode", "vector").header("Authorization", "Bearer " + a.token())).andReturn();
        }
    }

    @Nested
    @EnabledIfEnvironmentVariable(named = "QDRANT_TEST_URL", matches = ".+")
    @TestPropertySource(properties = {
            "forgeflow.retrieval.vector-store=qdrant",
            "forgeflow.retrieval.qdrant.url=${QDRANT_TEST_URL}",
            "forgeflow.retrieval.qdrant.collection=forgeflow_test"
    })
    class WithQdrant extends Base {
        @Test
        void vectorSearchIsAnsweredByQdrant() throws Exception {
            assertThat(vectors.name()).isEqualTo("qdrant");
            Account a = signup("qdrant");
            long id = project(a);
            MvcResult r = search(id, a, "dark mode toggle");
            assertThat(r.getResponse().getHeader(SearchController.DEGRADED_HEADER)).isNull();
            assertThat(json.readTree(r.getResponse().getContentAsString()).get(0).path("path").asText()).isEqualTo("theme.js");
        }
    }

    @Nested
    @TestPropertySource(properties = {
            "forgeflow.retrieval.vector-store=qdrant",
            "forgeflow.retrieval.qdrant.url=http://127.0.0.1:1"            // nothing listens here
    })
    class WithQdrantDown extends Base {
        @Test
        void searchStillAnswersFromPgvectorAndSaysItDegraded() throws Exception {
            Account a = signup("qdrant-down");
            long id = project(a);                       // indexing can't reach Qdrant: rows marked for retry
            MvcResult r = search(id, a, "dark mode toggle");
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(r.getResponse().getHeader(SearchController.DEGRADED_HEADER)).isEqualTo("true");
            assertThat(r.getResponse().getHeader(SearchController.MISSING_VECTORS_HEADER)).isNotNull();
            // Postgres kept every vector, so meaning-based search still finds it.
            assertThat(json.readTree(r.getResponse().getContentAsString()).get(0).path("path").asText()).isEqualTo("theme.js");
        }
    }
}
