package com.forgeflow.workspace;

import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Saving a file by hand: a write, with the same path rules the agent's tools apply. */
class SaveFileTest extends ApiTestSupport {

    private static String files(long id) {
        return "/api/v1/projects/" + id + "/files";
    }

    private JsonNode save(long id, Account as, String path, String content, int status) {
        return call(HttpMethod.PUT, files(id) + "/content", as, Map.of("path", path, "content", content), status);
    }

    @Test
    void anOwnerSavesAFileAndItIsReadableAndSearchableAtOnce() {
        Account a = signup("saver");
        long id = createProject(a, "hand-edits");

        JsonNode first = save(id, a, "/js/cart.js", "function applyDiscountCode(code) { return code; }", 200);
        assertThat(first.path("path").asText()).isEqualTo("js/cart.js");     // leading slash dropped
        assertThat(first.path("version").asInt()).isEqualTo(1);
        assertThat(save(id, a, "js/cart.js", "function applyDiscountCode(c) { return c.trim(); }", 200)
                .path("version").asInt()).isEqualTo(2);

        assertThat(get(files(id) + "/content?path=js/cart.js", a, 200).path("content").asText()).contains("trim");
        JsonNode hits = get("/api/v1/projects/" + id + "/search?q=applyDiscountCode", a, 200);
        assertThat(hits.get(0).path("path").asText()).isEqualTo("js/cart.js");
    }

    @Test
    void anEditorMaySaveAViewerMayNotAStrangerSeesNothing() {
        Account owner = signup("owner");
        Account editor = signup("editor");
        Account viewer = signup("viewer");
        Account stranger = signup("stranger");
        long id = createProject(owner, "shared");
        post("/api/v1/projects/" + id + "/members", owner, Map.of("email", editor.email(), "role", "EDITOR"), 201);
        post("/api/v1/projects/" + id + "/members", owner, Map.of("email", viewer.email(), "role", "VIEWER"), 201);

        save(id, editor, "a.js", "let a = 1;", 200);
        save(id, viewer, "b.js", "let b = 1;", 403);
        save(id, stranger, "c.js", "let c = 1;", 404);
        assertThat(get(files(id), owner, 200)).hasSize(1);
    }

    @Test
    void unsafeOrOversizedFilesAreRefused() {
        Account a = signup("careful");
        long id = createProject(a, "guarded");

        save(id, a, "../../etc/passwd", "x", 400);
        save(id, a, "   ", "x", 400);
        save(id, a, "big.js", "x".repeat(FileController.MAX_FILE_BYTES + 1), 413);
        assertThat(get(files(id), a, 200)).isEmpty();
    }
}
