package com.forgeflow.execution;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * A structural check for JavaScript, in pure Java.
 *
 * This is NOT a parser. It walks the source tracking strings, template
 * literals and comments so that delimiters inside them are ignored, then
 * verifies that (), [] and {} balance and that no string or block comment is
 * left open.
 *
 * Why a heuristic rather than a real parse: the hosted environment has no
 * Docker, therefore no Node, therefore no vm.Script. Rather than pretend the
 * check is as strong as the container one, this class is deliberately limited
 * and BuildResult says so in its output. It catches the failures that actually
 * occur - a missed brace, an unclosed paren, a runaway string - and it will
 * miss things a real parser would find, such as a misplaced keyword.
 */
final class StructuralJsCheck {

    private static final Map<Character, Character> CLOSES = Map.of(
            ')', '(', ']', '[', '}', '{');

    private StructuralJsCheck() {
    }

    /** @return one problem per line, empty when the file looks structurally sound. */
    static List<String> scan(String path, String src) {
        List<String> problems = new ArrayList<>();
        Deque<int[]> open = new ArrayDeque<>();   // { character, line }
        int i = 0;
        int line = 1;
        final int n = src.length();

        while (i < n) {
            char c = src.charAt(i);

            if (c == '\n') {
                line++;
                i++;
                continue;
            }

            // ---- comments ----
            if (c == '/' && i + 1 < n) {
                char next = src.charAt(i + 1);
                if (next == '/') {
                    while (i < n && src.charAt(i) != '\n') {
                        i++;
                    }
                    continue;
                }
                if (next == '*') {
                    int end = src.indexOf("*/", i + 2);
                    if (end < 0) {
                        problems.add(path + " line " + line + ": block comment is never closed");
                        return problems;
                    }
                    line += countNewlines(src, i, end);
                    i = end + 2;
                    continue;
                }
            }

            // ---- strings and template literals ----
            if (c == '"' || c == '\'' || c == '`') {
                int j = i + 1;
                boolean closed = false;
                while (j < n) {
                    char d = src.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    if (d == c) {
                        closed = true;
                        break;
                    }
                    // Only a template literal may span lines.
                    if (d == '\n') {
                        if (c != '`') {
                            break;
                        }
                        line++;
                    }
                    j++;
                }
                if (!closed) {
                    problems.add(path + " line " + line + ": string starting with " + c + " is never closed");
                    return problems;
                }
                i = j + 1;
                continue;
            }

            // ---- delimiters ----
            if (c == '(' || c == '[' || c == '{') {
                open.push(new int[]{c, line});
            } else if (CLOSES.containsKey(c)) {
                if (open.isEmpty() || open.peek()[0] != CLOSES.get(c)) {
                    problems.add(path + " line " + line + ": unexpected '" + c + "'");
                    return problems;
                }
                open.pop();
            }
            i++;
        }

        if (!open.isEmpty()) {
            int[] last = open.peek();
            problems.add(path + " line " + last[1] + ": '" + (char) last[0] + "' is never closed");
        }
        return problems;
    }

    private static int countNewlines(String s, int from, int to) {
        int count = 0;
        for (int k = from; k < to; k++) {
            if (s.charAt(k) == '\n') {
                count++;
            }
        }
        return count;
    }
}
