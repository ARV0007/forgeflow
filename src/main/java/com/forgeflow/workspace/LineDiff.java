package com.forgeflow.workspace;

import java.util.ArrayList;
import java.util.List;

/**
 * Line diff in the unified format (what `git diff` shows): hunks of changes
 * with three lines of context. Longest common subsequence by dynamic
 * programming - O(n·m), which is nothing for the files an agent writes (a few
 * hundred lines) and is capped for anything bigger.
 */
public final class LineDiff {

    public static final int CONTEXT = 3;
    /** Above n·m cells the table is skipped and the file is reported as replaced wholesale. */
    static final long MAX_CELLS = 4_000_000L;

    public record Result(String unified, int additions, int deletions, boolean truncated) {
    }

    private enum Op { SAME, ADD, DEL }

    private record Edit(Op op, String line, int oldLine, int newLine) {
    }

    private LineDiff() {
    }

    public static Result diff(String before, String after) {
        List<String> a = lines(before);
        List<String> b = lines(after);
        boolean truncated = (long) a.size() * b.size() > MAX_CELLS;
        List<Edit> edits = truncated ? replaceAll(a, b) : lcs(a, b);
        int adds = 0;
        int dels = 0;
        for (Edit e : edits) {
            if (e.op() == Op.ADD) {
                adds++;
            } else if (e.op() == Op.DEL) {
                dels++;
            }
        }
        return new Result(hunks(edits), adds, dels, truncated);
    }

    static List<String> lines(String s) {
        if (s == null || s.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(List.of(s.split("\n", -1)));
        if (out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);          // a trailing newline isn't an extra empty line
        }
        return out;
    }

    private static List<Edit> lcs(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        int[][] len = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                len[i][j] = a.get(i).equals(b.get(j)) ? len[i + 1][j + 1] + 1 : Math.max(len[i + 1][j], len[i][j + 1]);
            }
        }
        List<Edit> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) {
                out.add(new Edit(Op.SAME, a.get(i), i + 1, j + 1));
                i++;
                j++;
            } else if (len[i + 1][j] >= len[i][j + 1]) {
                out.add(new Edit(Op.DEL, a.get(i), i + 1, j));
                i++;
            } else {
                out.add(new Edit(Op.ADD, b.get(j), i, j + 1));
                j++;
            }
        }
        while (i < n) {
            out.add(new Edit(Op.DEL, a.get(i), i + 1, j));
            i++;
        }
        while (j < m) {
            out.add(new Edit(Op.ADD, b.get(j), i, j + 1));
            j++;
        }
        return out;
    }

    private static List<Edit> replaceAll(List<String> a, List<String> b) {
        List<Edit> out = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            out.add(new Edit(Op.DEL, a.get(i), i + 1, 0));
        }
        for (int j = 0; j < b.size(); j++) {
            out.add(new Edit(Op.ADD, b.get(j), a.size(), j + 1));
        }
        return out;
    }

    /** Group edits into hunks: each change with up to CONTEXT unchanged lines either side. */
    private static String hunks(List<Edit> edits) {
        StringBuilder out = new StringBuilder();
        int k = 0;
        while (k < edits.size()) {
            while (k < edits.size() && edits.get(k).op() == Op.SAME) {
                k++;
            }
            if (k == edits.size()) {
                break;
            }
            int start = Math.max(0, k - CONTEXT);
            int end = k;
            int sameRun = 0;
            while (end < edits.size()) {
                if (edits.get(end).op() == Op.SAME) {
                    sameRun++;
                    if (sameRun > 2 * CONTEXT) {
                        break;
                    }
                } else {
                    sameRun = 0;
                }
                end++;
            }
            end = Math.min(edits.size(), end - Math.max(0, sameRun - CONTEXT));

            int oldStart = 0;
            int newStart = 0;
            int oldCount = 0;
            int newCount = 0;
            StringBuilder body = new StringBuilder();
            for (int x = start; x < end; x++) {
                Edit e = edits.get(x);
                if (e.op() != Op.ADD) {
                    if (oldCount == 0) {
                        oldStart = e.oldLine();
                    }
                    oldCount++;
                }
                if (e.op() != Op.DEL) {
                    if (newCount == 0) {
                        newStart = e.newLine();
                    }
                    newCount++;
                }
                body.append(e.op() == Op.SAME ? ' ' : e.op() == Op.ADD ? '+' : '-').append(e.line()).append('\n');
            }
            if (oldCount == 0) {
                oldStart = edits.get(start).oldLine();
            }
            if (newCount == 0) {
                newStart = edits.get(start).newLine();
            }
            out.append("@@ -").append(oldStart).append(',').append(oldCount)
               .append(" +").append(newStart).append(',').append(newCount).append(" @@\n").append(body);
            k = end;
        }
        return out.toString();
    }
}
