package com.forgeflow.intelligence.retrieval;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts a file into overlapping windows of lines - the unit that gets embedded
 * and retrieved.
 *
 * Why not one vector per file? A 400-line file about a dozen things averages
 * into a vector close to none of them. Why not one per line? Too little
 * context to mean anything. ~40 lines is roughly one function or one CSS
 * block. The overlap means something sitting on a boundary still appears
 * whole in at least one chunk.
 */
public final class CodeChunker {

    static final int MAX_LINES = 40;
    static final int MAX_CHARS = 1_800;
    static final int OVERLAP = 5;
    /** When a window is full, look this far back for a blank line to end on - a natural seam. */
    static final int SEAM_LOOKBACK = 10;

    public record Chunk(int index, int startLine, int endLine, String text) {
    }

    private CodeChunker() {
    }

    /** Lines are 1-based and inclusive, matching what an editor shows. */
    public static List<Chunk> chunk(String content) {
        List<Chunk> out = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return out;
        }
        String[] lines = content.split("\n", -1);
        int start = 0;
        while (start < lines.length) {
            int end = start;           // exclusive
            int chars = 0;
            while (end < lines.length && end - start < MAX_LINES
                    && (chars + lines[end].length() + 1 <= MAX_CHARS || end == start)) {
                chars += lines[end].length() + 1;
                end++;
            }
            if (end < lines.length) {
                for (int i = end - 1; i > end - SEAM_LOOKBACK && i > start + OVERLAP; i--) {
                    if (lines[i].isBlank()) {
                        end = i + 1;
                        break;
                    }
                }
            }
            String text = String.join("\n", java.util.Arrays.copyOfRange(lines, start, end)).strip();
            if (!text.isEmpty()) {
                out.add(new Chunk(out.size(), start + 1, end, text));
            }
            if (end >= lines.length) {
                break;
            }
            // Step back by the overlap, but always move forward.
            start = Math.max(start + 1, end - OVERLAP);
        }
        return out;
    }
}
