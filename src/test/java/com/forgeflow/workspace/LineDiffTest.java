package com.forgeflow.workspace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LineDiffTest {

    @Test
    void aOneLineChangeIsOneHunkWithContext() {
        String before = "a\nb\nc\nd\ne\nf\ng\nh\n";
        String after = "a\nb\nc\nd\nE\nf\ng\nh\n";
        LineDiff.Result r = LineDiff.diff(before, after);
        assertThat(r.additions()).isEqualTo(1);
        assertThat(r.deletions()).isEqualTo(1);
        assertThat(r.unified()).isEqualTo("""
                @@ -2,7 +2,7 @@
                 b
                 c
                 d
                -e
                +E
                 f
                 g
                 h
                """);
    }

    @Test
    void farApartChangesAreSeparateHunksAndAddedFilesAreAllPluses() {
        StringBuilder before = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            before.append("line ").append(i).append('\n');
        }
        String after = before.toString().replace("line 2\n", "line two\n").replace("line 28\n", "line twenty-eight\n");
        LineDiff.Result r = LineDiff.diff(before.toString(), after);
        assertThat(r.unified().lines().filter(l -> l.startsWith("@@"))).hasSize(2);
        assertThat(r.additions()).isEqualTo(2);

        LineDiff.Result added = LineDiff.diff("", "x\ny\n");
        assertThat(added.unified()).isEqualTo("@@ -0,0 +1,2 @@\n+x\n+y\n");
        assertThat(LineDiff.diff("same\n", "same\n").unified()).isEmpty();
    }
}
