package com.forgeflow.workspace;

import com.forgeflow.shared.storage.ObjectStore;
import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static com.forgeflow.support.ScriptedLlm.write;
import static org.assertj.core.api.Assertions.assertThat;

/** Mark and sweep: what no row points at goes; what the project or its history needs stays. */
@EnabledIfEnvironmentVariable(named = "S3_TEST_ENDPOINT", matches = ".+")
@TestPropertySource(properties = {
        "forgeflow.storage.files=s3",
        "forgeflow.storage.s3.endpoint=${S3_TEST_ENDPOINT}",
        "forgeflow.storage.s3.bucket=forgeflow-test",
        "forgeflow.storage.s3.access-key=${S3_TEST_ACCESS_KEY:minioadmin}",
        "forgeflow.storage.s3.secret-key=${S3_TEST_SECRET_KEY:minioadmin}"
})
class BlobSweeperTest extends ApiTestSupport {

    @Autowired
    ObjectStore store;
    @Autowired
    BlobSweeper sweeper;
    @Autowired
    ProjectFileService files;

    private void save(Account a, long id, String path, String content) {
        call(HttpMethod.PUT, "/api/v1/projects/" + id + "/files/content", a, Map.of("path", path, "content", content), 200);
    }

    private static String key(long id, String content) {
        return ProjectFileService.blobKey(id, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void garbageGoesWhileHistoryAndTheCurrentFilesStay() {
        Account a = signup("sweeper");
        long id = createProject(a, "swept");
        // An AI run writes v1: the run's checkpoint now refers to v1's blob.
        llm.then(calls(write("index.html", "<h1>v1</h1>"))).then(calls(finish("Built.")));
        post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "v1"), 200);
        save(a, id, "index.html", "<h1>v2</h1>");        // hand edits: no checkpoint of their own...
        save(a, id, "index.html", "<h1>v3</h1>");        // ...so v2 is now referenced by nothing
        String stray = "projects/" + id + "/blobs/" + "0".repeat(64);             // a write whose row never landed
        store.put(stray, "x".getBytes(StandardCharsets.UTF_8), "text/plain");

        // Inside the grace period nothing is touched: it might be a write in flight.
        assertThat(sweeper.sweep(Duration.ofHours(1)).deleted()).isZero();
        assertThat(store.get(stray)).isPresent();

        BlobSweeper.Report r = sweeper.sweep(Duration.ZERO);
        assertThat(r.ran()).isTrue();
        assertThat(store.get(stray)).isEmpty();
        assertThat(store.get(key(id, "<h1>v2</h1>"))).isEmpty();
        assertThat(store.get(key(id, "<h1>v1</h1>"))).isPresent();            // version history still needs it
        assertThat(store.get(key(id, "<h1>v3</h1>"))).isPresent();            // the project's current file
        assertThat(files.read(id, "index.html")).contains("<h1>v3</h1>");
        assertThat(sweeper.sweep(Duration.ZERO).deleted()).isZero();         // nothing left to collect
    }
}
