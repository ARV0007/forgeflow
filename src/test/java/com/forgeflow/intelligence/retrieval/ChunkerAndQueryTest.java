package com.forgeflow.intelligence.retrieval;

import com.forgeflow.shared.llm.Embedder;
import com.forgeflow.shared.llm.HashingEmbedder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkerAndQueryTest {

    @Test
    void aSmallFileIsOneChunkWithItsLineRange() {
        List<CodeChunker.Chunk> chunks = CodeChunker.chunk("a\nb\nc");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).startLine()).isEqualTo(1);
        assertThat(chunks.get(0).endLine()).isEqualTo(3);
        assertThat(CodeChunker.chunk("   \n  ")).isEmpty();
    }

    @Test
    void aLongFileIsCoveredByOverlappingChunks() {
        String file = IntStream.rangeClosed(1, 130).mapToObj(i -> "line " + i).collect(Collectors.joining("\n"));
        List<CodeChunker.Chunk> chunks = CodeChunker.chunk(file);

        assertThat(chunks.size()).isGreaterThan(3);
        assertThat(chunks.get(0).startLine()).isEqualTo(1);
        assertThat(chunks.get(chunks.size() - 1).endLine()).isEqualTo(130);
        for (int i = 1; i < chunks.size(); i++) {
            // Overlap: no line falls between two chunks.
            assertThat(chunks.get(i).startLine()).isLessThanOrEqualTo(chunks.get(i - 1).endLine());
            assertThat(chunks.get(i).startLine()).isGreaterThan(chunks.get(i - 1).startLine());
            assertThat(chunks.get(i).index()).isEqualTo(i);
        }
    }

    @Test
    void oneEnormousLineStillMakesProgress() {
        String file = "x".repeat(10_000) + "\n" + "y".repeat(10_000);
        assertThat(CodeChunker.chunk(file)).hasSize(2);
    }

    @Test
    void chunksPreferToEndOnABlankLine() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 60; i++) {
            sb.append(i == 35 ? "" : "code " + i).append('\n');
        }
        assertThat(CodeChunker.chunk(sb.toString()).get(0).endLine()).isEqualTo(35);
    }

    @Test
    void userTextCannotBecomeTsquerySyntax() {
        assertThat(CodeIndex.keywordQuery("renderTodos & 'drop' | !x (a:*)")).isEqualTo("rendertodos | drop");
        assertThat(CodeIndex.keywordQuery("!!! ?? &")).isNull();
    }

    @Test
    void theHashingEmbedderSplitsIdentifiersAndRanksBySharedWords() {
        HashingEmbedder e = new HashingEmbedder();
        List<float[]> v = e.embed(List.of("function renderTodos(list)", "render the todos", "body { color: red }"),
                Embedder.Kind.DOCUMENT);
        assertThat(v.get(0)).hasSize(Embedder.DIMENSIONS);
        assertThat(cos(v.get(0), v.get(1))).isGreaterThan(cos(v.get(0), v.get(2)));
        assertThat(cos(v.get(0), v.get(0))).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    }

    private static double cos(float[] a, float[] b) {
        double d = 0;
        for (int i = 0; i < a.length; i++) {
            d += a[i] * b[i];
        }
        return d;
    }
}
