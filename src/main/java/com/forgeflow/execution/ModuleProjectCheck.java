package com.forgeflow.execution;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The build check for a React (Vite-layout) project, in pure Java.
 *
 * What it proves: package.json is valid and names react and react-dom;
 * index.html loads an entry module that exists; every relative import lands
 * on a file; every package import is declared; braces balance. Together those
 * catch what actually goes wrong when a model writes a multi-file React app:
 * a component imported but never written, a path typo, a library used but not
 * added, a truncated file.
 *
 * What it can't prove: that the JSX parses. There is no JavaScript engine in
 * this JVM and no Node on the host. The real compile happens in the browser,
 * where the preview's runner compiles each file with Sucrase - and a compile
 * error there goes to the Logs Stream and back to the agent through the same
 * runtime loop as any other error.
 */
final class ModuleProjectCheck {

    static final List<String> CODE = List.of(".jsx", ".js", ".tsx", ".ts", ".mjs");
    private static final List<String> TRY = List.of("", ".jsx", ".js", ".tsx", ".ts", ".mjs",
            "/index.jsx", "/index.js", "/index.tsx", "/index.ts");

    /** import x from '...'; import '...'; import {a,\n b} from '...' - at the start of a line. */
    private static final Pattern IMPORT = Pattern.compile(
            "^\\s*import\\s+(?:[\\w*{}\\s,$]+?\\s+from\\s+)?['\"]([^'\"]+)['\"]", Pattern.MULTILINE);
    private static final Pattern REEXPORT = Pattern.compile(
            "^\\s*export\\s+(?:\\*(?:\\s+as\\s+\\w+)?|\\{[^}]*})\\s+from\\s+['\"]([^'\"]+)['\"]", Pattern.MULTILINE);
    private static final Pattern DYNAMIC = Pattern.compile("\\bimport\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");
    private static final Pattern MODULE_SCRIPT = Pattern.compile("<script\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SRC = Pattern.compile("\\bsrc\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern TYPE_MODULE = Pattern.compile("\\btype\\s*=\\s*['\"]module['\"]", Pattern.CASE_INSENSITIVE);

    private ModuleProjectCheck() {
    }

    /** A project with a package.json is built as a module project. */
    static boolean applies(Map<String, String> files) {
        return files.containsKey("package.json");
    }

    static List<String> problems(Map<String, String> files) {
        List<String> problems = new ArrayList<>();

        Set<String> declared = new LinkedHashSet<>();
        boolean pkgReadable = true;
        try {
            JsonNode pkg = new ObjectMapper().readTree(files.get("package.json"));
            if (!pkg.isObject()) {
                throw new IllegalArgumentException("not an object");
            }
            for (String section : List.of("dependencies", "devDependencies", "peerDependencies")) {
                pkg.path(section).propertyNames().forEach(declared::add);
            }
            for (String required : List.of("react", "react-dom")) {
                if (!pkg.path("dependencies").has(required)) {
                    problems.add("package.json: \"" + required + "\" must be listed in dependencies");
                }
            }
        } catch (RuntimeException e) {
            pkgReadable = false;     // then "not declared" would be noise for every import
            problems.add("package.json is not valid JSON - " + firstLine(e.getMessage()));
        }

        String html = files.get("index.html");
        if (html == null) {
            problems.add("index.html is missing - a Vite project starts from index.html at the root");
        } else {
            List<String> entries = entryModules(html);
            if (entries.isEmpty()) {
                problems.add("index.html loads no module - add <script type=\"module\" src=\"/src/main.jsx\"></script>");
            }
            for (String entry : entries) {
                if (resolve(files, "index.html", entry) == null) {
                    problems.add("index.html loads " + entry + ", which does not exist");
                }
            }
        }

        for (Map.Entry<String, String> f : files.entrySet()) {
            String path = f.getKey();
            if (CODE.stream().noneMatch(path::endsWith)) {
                continue;
            }
            boolean jsx = path.endsWith(".jsx") || path.endsWith(".tsx");
            problems.addAll(StructuralJsCheck.scan(path, f.getValue(), jsx));
            for (String spec : imports(f.getValue())) {
                if (spec.startsWith(".") || spec.startsWith("/")) {
                    if (resolve(files, path, spec) == null) {
                        problems.add(path + " imports '" + spec + "', but no such file exists");
                    }
                } else if (pkgReadable && !spec.startsWith("http:") && !spec.startsWith("https:")) {
                    String pkgName = packageName(spec);
                    if (!declared.contains(pkgName)) {
                        problems.add(path + " imports '" + spec + "', but \"" + pkgName
                                + "\" is not in package.json - add it to dependencies or don't use it");
                    }
                }
            }
        }
        return problems;
    }

    /** The src of every <script type="module" src="..."> in a page. */
    static List<String> entryModules(String html) {
        List<String> out = new ArrayList<>();
        Matcher tag = MODULE_SCRIPT.matcher(html);
        while (tag.find()) {
            String t = tag.group();
            Matcher src = SRC.matcher(t);
            if (TYPE_MODULE.matcher(t).find() && src.find()) {
                out.add(src.group(1).trim());
            }
        }
        return out;
    }

    static Set<String> imports(String code) {
        Set<String> out = new LinkedHashSet<>();
        for (Pattern p : List.of(IMPORT, REEXPORT, DYNAMIC)) {
            Matcher m = p.matcher(code);
            while (m.find()) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    /** 'react-dom/client' -> react-dom, '@scope/pkg/sub' -> @scope/pkg */
    static String packageName(String spec) {
        String[] parts = spec.split("/");
        return spec.startsWith("@") && parts.length > 1 ? parts[0] + "/" + parts[1] : parts[0];
    }

    /** The project file an import lands on, trying the extensions Vite would; null if none. */
    static String resolve(Map<String, String> files, String from, String spec) {
        String base = normalise(from, spec);
        if (base == null) {
            return null;
        }
        for (String suffix : TRY) {
            if (files.containsKey(base + suffix)) {
                return base + suffix;
            }
        }
        return null;
    }

    private static String normalise(String from, String spec) {
        String dir = from.contains("/") ? from.substring(0, from.lastIndexOf('/') + 1) : "";
        String joined = spec.startsWith("/") ? spec.substring(1) : dir + spec;
        List<String> parts = new ArrayList<>();
        for (String seg : joined.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            if (seg.equals("..")) {
                if (parts.isEmpty()) {
                    return null;
                }
                parts.remove(parts.size() - 1);
                continue;
            }
            parts.add(seg);
        }
        return String.join("/", parts);
    }

    /** Jackson's message without the parser-location boilerplate. */
    private static String firstLine(String s) {
        String line = s == null ? "" : s.split("\n", 2)[0];
        int cut = line.indexOf(" (start marker");
        if (cut < 0) {
            cut = line.indexOf(" at [Source");
        }
        return cut > 0 ? line.substring(0, cut) : line;
    }
}
