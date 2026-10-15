package com.forgeflow.workspace;

import com.forgeflow.support.ApiTestSupport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Importing a codebase: what goes in, what is left out (and why), that the
 * old files stay in History, and that hostile zips are refused.
 */
class ImportTest extends ApiTestSupport {

    /** Plays github.com: serves /{owner}/{repo}/archive/{ref}.zip from a map. */
    static final HttpServer github;
    static final Map<String, byte[]> repos = new LinkedHashMap<>();
    static final List<String> asked = new CopyOnWriteArrayList<>();

    static {
        try {
            github = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        github.createContext("/", ex -> {
            asked.add(ex.getRequestURI().getPath());
            byte[] body = repos.get(ex.getRequestURI().getPath());
            ex.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : body.length);
            if (body != null) {
                try (OutputStream o = ex.getResponseBody()) {
                    o.write(body);
                }
            }
            ex.close();
        });
        github.start();
    }

    @DynamicPropertySource
    static void fakeGithub(DynamicPropertyRegistry r) {
        r.add("forgeflow.import.github-base", () -> "http://127.0.0.1:" + github.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        github.stop(0);
    }

    static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** A GitHub-shaped zip: everything under "shop-main/", plus the things an import must leave out. */
    static byte[] shopRepo() {
        Map<String, byte[]> e = new LinkedHashMap<>();
        e.put("shop-main/index.html", utf8("<!doctype html><html><body><script src=\"src/cart.js\"></script></body></html>"));
        e.put("shop-main/src/cart.js", utf8("// Cart\nfunction cartTotal(items) {\n  return items.reduce((s, i) => s + i.price, 0);\n}\n"));
        e.put("shop-main/src/promo.js", utf8("function applyPromoCode(code) { return code === 'SAVE10' ? 0.1 : 0; }\n"));
        e.put("shop-main/README.md", utf8("# Shop\n"));
        e.put("shop-main/node_modules/left-pad/index.js", utf8("module.exports = s => s;"));
        e.put("shop-main/.git/config", utf8("[core]"));
        e.put("shop-main/logo.png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 13});
        e.put("shop-main/data.bin", new byte[]{1, 2, 0, 3});
        e.put("shop-main/huge.json", utf8("[" + "1,".repeat(150_000) + "1]"));
        e.put("shop-main/../escape.js", utf8("alert('zip slip')"));
        return zip(e);
    }

    private JsonNode uploadZip(long id, Account a, byte[] body, int status) {
        try {
            MvcResult r = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/projects/" + id + "/import/zip").param("name", "shop.zip")
                    .header("Authorization", "Bearer " + a.token()).contentType("application/zip").content(body))
                    .andReturn();
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
            String text = r.getResponse().getContentAsString();
            return text.isEmpty() ? null : json.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aZipImportKeepsTheCodeAndSaysWhatItLeftOut() {
        Account a = signup("import");
        long id = createProject(a, "shop");
        // Something already there: replaced by the import, kept in History.
        call(org.springframework.http.HttpMethod.PUT, "/api/v1/projects/" + id + "/files/content", a,
                Map.of("path", "old.txt", "content", "before the import"), 200);

        JsonNode r = uploadZip(id, a, shopRepo(), 200);

        assertThat(r.path("imported").asInt()).isEqualTo(4);
        assertThat(r.path("removed").asInt()).isEqualTo(1);
        assertThat(r.path("source").asText()).isEqualTo("Imported shop.zip");
        JsonNode skipped = r.path("skipped");
        assertThat(skipped.path("IGNORED_FOLDER").asInt()).isEqualTo(2);    // node_modules, .git
        assertThat(skipped.path("BINARY").asInt()).isEqualTo(2);            // logo.png (extension), data.bin (content)
        assertThat(skipped.path("TOO_LARGE").asInt()).isEqualTo(1);         // huge.json
        assertThat(skipped.path("UNSAFE_PATH").asInt()).isEqualTo(1);       // ../escape.js

        JsonNode tree = get("/api/v1/projects/" + id + "/files", a, 200);
        assertThat(tree.findValuesAsString("path"))
                .containsExactlyInAnyOrder("README.md", "index.html", "src/cart.js", "src/promo.js");

        // History: the old files, then the import.
        JsonNode history = get("/api/v1/projects/" + id + "/checkpoints", a, 200);
        assertThat(history.get(0).path("kind").asText()).isEqualTo("IMPORT");
        assertThat(history.get(0).path("label").asText()).isEqualTo("Imported shop.zip");
        assertThat(history.get(1).path("label").asText()).isEqualTo("Before imported shop.zip");

        // Searchable straight away (the code.generated event re-indexed it).
        JsonNode hits = get("/api/v1/projects/" + id + "/search?q=applyPromoCode", a, 200);
        assertThat(hits.get(0).path("path").asText()).isEqualTo("src/promo.js");
    }

    @Test
    void aGithubRepoImportsByUrlAndOneFolderCanBePicked() {
        Account a = signup("import-gh");
        long id = createProject(a, "from-github");
        repos.put("/acme/shop/archive/HEAD.zip", shopRepo());
        repos.put("/acme/shop/archive/dev.zip", shopRepo());

        JsonNode r = post("/api/v1/projects/" + id + "/import/github", a, Map.of("url", "https://github.com/acme/shop"), 200);
        assertThat(r.path("imported").asInt()).isEqualTo(4);
        assertThat(r.path("source").asText()).isEqualTo("Imported from github.com/acme/shop");

        JsonNode sub = post("/api/v1/projects/" + id + "/import/github", a,
                Map.of("url", "github.com/acme/shop/tree/dev/src"), 200);
        assertThat(sub.path("imported").asInt()).isEqualTo(2);
        assertThat(sub.path("source").asText()).isEqualTo("Imported from github.com/acme/shop/src @ dev");
        assertThat(get("/api/v1/projects/" + id + "/files", a, 200).findValuesAsString("path"))
                .containsExactlyInAnyOrder("cart.js", "promo.js");
        assertThat(asked).contains("/acme/shop/archive/dev.zip");

        // Not a repo address, a repo that isn't there, a ref that walks out.
        assertThat(post("/api/v1/projects/" + id + "/import/github", a, Map.of("url", "https://evil.example/acme/shop"), 400)
                .path("detail").asText()).contains("GitHub repo address");
        assertThat(post("/api/v1/projects/" + id + "/import/github", a, Map.of("url", "https://github.com/acme/nope"), 400)
                .path("detail").asText()).contains("no public repo");
        post("/api/v1/projects/" + id + "/import/github", a, Map.of("url", "https://github.com/acme/shop/tree/../../x"), 400);
    }

    @Test
    void hostileOrUselessZipsAreRefusedBeforeAnythingChanges() {
        Account a = signup("import-bad");
        long id = createProject(a, "safe");
        call(org.springframework.http.HttpMethod.PUT, "/api/v1/projects/" + id + "/files/content", a,
                Map.of("path", "keep.txt", "content", "still here"), 200);

        assertThat(uploadZip(id, a, utf8("this is not a zip"), 400).path("detail").asText()).contains("zip");

        // Under the per-file cap one by one, 38 MB together: stopped by the
        // running total of bytes actually inflated, not by any size the zip claims.
        Map<String, byte[]> bomb = new LinkedHashMap<>();
        for (int i = 0; i < 200; i++) {
            bomb.put("b/f" + i + ".txt", utf8("x".repeat(190_000)));
        }
        assertThat(uploadZip(id, a, zip(bomb), 400).path("detail").asText()).contains("more than 30 MB");

        Map<String, byte[]> onlyJunk = new LinkedHashMap<>();
        onlyJunk.put("r/node_modules/a.js", utf8("x"));
        onlyJunk.put("r/pic.png", utf8("x"));
        assertThat(uploadZip(id, a, zip(onlyJunk), 400).path("detail").asText()).contains("Nothing to import");

        // Nothing changed.
        assertThat(get("/api/v1/projects/" + id + "/files", a, 200).findValuesAsString("path")).containsExactly("keep.txt");
    }

    @Test
    void viewersCannotImportAndStrangersDontSeeTheProject() {
        Account owner = signup("import-owner");
        Account viewer = signup("import-viewer");
        Account stranger = signup("import-stranger");
        long id = createProject(owner, "mine");
        post("/api/v1/projects/" + id + "/members", owner, Map.of("email", viewer.email(), "role", "VIEWER"), 201);

        uploadZip(id, viewer, shopRepo(), 403);
        uploadZip(id, stranger, shopRepo(), 404);
        assertThat(get("/api/v1/projects/" + id + "/files", owner, 200)).isEmpty();
    }

    @Test
    void theRulesOnTheirOwn() {
        assertThat(ProjectImporter.commonRoot(java.util.Set.of("r/a", "r/b/c"))).isEqualTo("r/");
        assertThat(ProjectImporter.commonRoot(java.util.Set.of("r/a", "s/b"))).isEmpty();
        assertThat(ProjectImporter.unsafe("C:/windows/x")).isTrue();
        assertThat(ProjectImporter.unsafe("a\\b.js")).isTrue();
        assertThat(ProjectImporter.unsafe("a/../b.js")).isTrue();
        assertThat(ProjectImporter.unsafe("src/ok.js")).isFalse();
        assertThat(ProjectImporter.isText(utf8("héllo"))).isTrue();
        assertThat(ProjectImporter.isText(new byte[]{(byte) 0xC3, 0x28})).isFalse();   // broken UTF-8
    }
}
