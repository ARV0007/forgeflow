package com.forgeflow.intelligence.retrieval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Against a real Qdrant (QDRANT_TEST_URL: CI's service container, or a local binary). */
@EnabledIfEnvironmentVariable(named = "QDRANT_TEST_URL", matches = ".+")
class QdrantVectorIndexIntegrationTest {

    private final QdrantVectorIndex qdrant =
            new QdrantVectorIndex(System.getenv("QDRANT_TEST_URL"), "it_" + UUID.randomUUID().toString().replace("-", ""), null);

    private static float[] v(float... xs) {
        return xs;
    }

    @Test
    void nearestIsFilteredByProjectAndModelAndFollowsReplacements() {
        assertThat(qdrant.nearest(1L, "m", v(1, 0, 0), 5)).isEmpty();          // no collection yet: empty, not an error

        qdrant.replaceFile(1L, "cart.js", "m", List.of(11L, 12L), List.of(v(1, 0, 0), v(0, 1, 0)));
        qdrant.replaceFile(1L, "theme.js", "m", List.of(13L), List.of(v(0, 0, 1)));
        qdrant.replaceFile(2L, "other.js", "m", List.of(21L), List.of(v(1, 0, 0)));     // another project, same vector
        qdrant.replaceFile(1L, "old.js", "old-model", List.of(31L), List.of(v(1, 0, 0)));  // another model

        assertThat(qdrant.nearest(1L, "m", v(0.9f, 0.1f, 0), 10)).containsExactly(11L, 12L, 13L);
        assertThat(qdrant.nearest(2L, "m", v(1, 0, 0), 10)).containsExactly(21L);

        // Re-indexing a file replaces its points rather than adding to them.
        qdrant.replaceFile(1L, "cart.js", "m", List.of(14L), List.of(v(0, 1, 0)));
        assertThat(qdrant.nearest(1L, "m", v(0.2f, 1, 0.1f), 10)).containsExactly(14L, 13L).doesNotContain(11L, 12L);

        qdrant.removeFile(1L, "theme.js");
        assertThat(qdrant.nearest(1L, "m", v(0, 0, 1), 10)).containsExactly(14L);
    }
}
