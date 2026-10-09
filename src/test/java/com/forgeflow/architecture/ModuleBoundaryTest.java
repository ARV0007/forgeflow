package com.forgeflow.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The modular monolith's rules, enforced. architecture.md section 17.1 says
 * modules talk through each other's service classes and never reach into
 * each other's persistence. Written down, that is a hope. Here, it fails the
 * build.
 *
 * Three rules:
 *   1. Dependencies only run along the allowed arrows below - which form a
 *      DAG, so there can be no cycles.
 *   2. No module imports another module's *Repository.
 *   3. No module imports another module's @Entity class. Entities are
 *      persistence; what crosses a boundary is a service call or a DTO.
 *
 * This is what makes "split into services later" a mechanical job: every
 * cross-module call is already one that could become an HTTP call.
 */
class ModuleBoundaryTest {

    private static final Path SOURCES = Path.of("src/main/java/com/forgeflow");
    private static final Pattern IMPORT = Pattern.compile("^import (?:static )?com\\.forgeflow\\.(\\w+)\\.([\\w.]+);", Pattern.MULTILINE);

    /** module -> the modules it may depend on. shared depends on nothing. */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "shared", Set.of(),
            "account", Set.of("shared"),
            "billing", Set.of("shared", "account"),
            "workspace", Set.of("shared", "account", "billing"),
            "execution", Set.of("shared", "workspace", "billing"),
            "intelligence", Set.of("shared", "workspace", "execution", "billing"),
            "chat", Set.of("shared", "workspace", "intelligence", "billing"),
            "mcp", Set.of("shared", "account", "workspace", "intelligence", "billing")
    );

    record Source(String module, String className, String text) {
    }

    @Test
    void modulesOnlyTalkThroughTheirPublicFaces() throws IOException {
        List<Source> sources = load();
        assertThat(sources).hasSizeGreaterThan(50);

        Set<String> entities = new HashSet<>();
        for (Source s : sources) {
            if (s.text().contains("\n@Entity")) {
                entities.add(s.module() + "." + s.className());
            }
        }
        assertThat(entities).contains("workspace.Project", "billing.UsageLog");   // the scan works

        List<String> violations = new ArrayList<>();
        for (Source s : sources) {
            if (s.module().equals("root")) {
                continue;               // ForgeflowApplication
            }
            Set<String> allowed = ALLOWED.get(s.module());
            assertThat(allowed).as("module %s is not in the rule table", s.module()).isNotNull();

            Matcher m = IMPORT.matcher(s.text());
            while (m.find()) {
                String target = m.group(1);
                String type = m.group(2);
                if (target.equals(s.module())) {
                    continue;
                }
                String where = s.module() + "/" + s.className() + " imports " + target + "." + type;
                if (!allowed.contains(target)) {
                    violations.add(where + "  - " + s.module() + " may not depend on " + target);
                }
                String simple = type.contains(".") ? type.substring(type.lastIndexOf('.') + 1) : type;
                if (simple.endsWith("Repository")) {
                    violations.add(where + "  - another module's repository");
                }
                if (entities.contains(target + "." + type)) {
                    violations.add(where + "  - another module's @Entity");
                }
            }
        }
        assertThat(violations).as("module boundary violations").isEmpty();
    }

    @Test
    void theAllowedArrowsHaveNoCycles() {
        for (String start : ALLOWED.keySet()) {
            assertThat(reaches(start, start, new HashSet<>())).as("cycle through %s", start).isFalse();
        }
    }

    private static boolean reaches(String from, String target, Set<String> seen) {
        for (String next : ALLOWED.get(from)) {
            if (next.equals(target)) {
                return true;
            }
            if (seen.add(next) && reaches(next, target, seen)) {
                return true;
            }
        }
        return false;
    }

    private static List<Source> load() throws IOException {
        List<Source> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Path rel = SOURCES.relativize(p);
                String module = rel.getNameCount() == 1 ? "root" : rel.getName(0).toString();
                String file = rel.getFileName().toString();
                String className = rel.getNameCount() > 2
                        ? rel.subpath(1, rel.getNameCount()).toString().replace('/', '.').replace(".java", "")
                        : file.replace(".java", "");
                out.add(new Source(module, className, Files.readString(p)));
            }
        }
        return out;
    }
}
