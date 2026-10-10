package com.forgeflow.intelligence.retrieval;

import com.forgeflow.support.ApiTestSupport;
import com.forgeflow.workspace.ProjectFileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retrieval eval's fixture and questions, run in CI as a regression
 * guard. Tests use the offline hashing embedder, which matches words, not
 * meaning - so this checks only the questions word matching should get
 * (exact identifiers, plain descriptions). The real numbers, paraphrases
 * included, come from evals/run_retrieval_eval.py against Gemini embeddings.
 */
class RetrievalEvalTest extends ApiTestSupport {

    private static final Path EVAL = Path.of("evals", "retrieval");

    @Autowired
    CodeIndex index;
    @Autowired
    ProjectFileService files;

    @Test
    void identifiersAndDescriptionsFindTheirFileInTheTopFive() throws IOException {
        Account a = signup("retrieval-eval");
        long id = createProject(a, "freshcart");
        Path fixture = EVAL.resolve("fixture");
        try (Stream<Path> walk = Files.walk(fixture)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String rel = fixture.relativize(p).toString().replace('\\', '/');
                files.write(id, rel, Files.readString(p), a.id());
            }
        }
        assertThat(files.list(id)).hasSizeGreaterThanOrEqualTo(25);

        JsonNode queries = new ObjectMapper().readTree(EVAL.resolve("queries.json").toFile()).path("queries");
        List<String> misses = new ArrayList<>();
        int asked = 0;
        for (JsonNode q : queries) {
            if (q.path("kind").asText().equals("paraphrase")) {
                continue;        // needs real embeddings; measured by the live eval
            }
            asked++;
            Set<String> good = new LinkedHashSet<>();
            good.add(q.path("expect").asText());
            q.path("accept").forEach(n -> good.add(n.asText()));

            List<String> ranked = new ArrayList<>();
            for (CodeIndex.SearchHit h : index.search(id, q.path("q").asText(), 20, CodeIndex.Mode.HYBRID)) {
                if (!ranked.contains(h.path())) {
                    ranked.add(h.path());
                }
            }
            List<String> top5 = ranked.subList(0, Math.min(5, ranked.size()));
            if (top5.stream().noneMatch(good::contains)) {
                misses.add(q.path("q").asText() + " -> " + top5);
            }
        }
        assertThat(asked).isEqualTo(28);
        // Word matching should get every one of these. Measured: 28/28.
        assertThat(misses).as("questions whose file was not in the top 5").isEmpty();
    }
}
