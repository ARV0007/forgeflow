package com.forgeflow.workspace;

import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole app with file contents in object storage (MinIO in CI, moto on a
 * laptop): rows hold metadata and a content-addressed key, the bytes live in
 * the bucket, and everything that reads files - the API, the agent, search,
 * the zip - doesn't notice.
 */
@EnabledIfEnvironmentVariable(named = "S3_TEST_ENDPOINT", matches = ".+")
@TestPropertySource(properties = {
        "forgeflow.storage.files=s3",
        "forgeflow.storage.s3.endpoint=${S3_TEST_ENDPOINT}",
        "forgeflow.storage.s3.bucket=forgeflow-test",
        "forgeflow.storage.s3.access-key=${S3_TEST_ACCESS_KEY:minioadmin}",
        "forgeflow.storage.s3.secret-key=${S3_TEST_SECRET_KEY:minioadmin}"
})
class ObjectStorageModeTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ProjectFileService files;

    @Test
    void contentsLiveInTheBucketAndEveryReaderStillWorks() throws Exception {
        assertThat(files.storageDescription()).startsWith("s3://forgeflow-test");
        Account a = signup("blobs");
        long id = createProject(a, "in-a-bucket");
        String js = "function applyDiscount(total) { return total * 0.9; }";
        call(HttpMethod.PUT, "/api/v1/projects/" + id + "/files/content", a, Map.of("path", "app.js", "content", js), 200);
        call(HttpMethod.PUT, "/api/v1/projects/" + id + "/files/content", a, Map.of("path", "copy.js", "content", js), 200);
        call(HttpMethod.PUT, "/api/v1/projects/" + id + "/files/content", a,
                Map.of("path", "index.html", "content", "<!DOCTYPE html><html><body><h1>Shop</h1><script src=\"app.js\"></script></body></html>"), 200);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT content, object_key FROM project_files WHERE project_id = ? AND path = 'app.js'", id);
        assertThat(row.get("content")).isNull();
        assertThat((String) row.get("object_key")).startsWith("projects/" + id + "/blobs/");
        String copyKey = jdbc.queryForObject(
                "SELECT object_key FROM project_files WHERE project_id = ? AND path = 'copy.js'", String.class, id);
        assertThat(copyKey).isEqualTo(row.get("object_key"));            // same bytes, same object

        // The API
        assertThat(get("/api/v1/projects/" + id + "/files/content?path=app.js", a, 200).path("content").asText()).isEqualTo(js);
        // Search (chunks are built from the bucket's copy)
        assertThat(get("/api/v1/projects/" + id + "/search?q=applyDiscount", a, 200).get(0).path("path").asText())
                .isIn("app.js", "copy.js");
        // The agent: edit_file reads, changes and writes back through the bucket
        llm.then(calls(new com.forgeflow.shared.llm.ToolCall("edit_file",
                        Map.of("path", "app.js", "old_text", "0.9", "new_text", "0.8"))))
           .then(calls(finish("Bigger discount.")));
        assertThat(post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "20% off"), 200)
                .path("status").asText()).isEqualTo("SUCCEEDED");                // and the build gate read it from the bucket too
        assertThat(files.read(id, "app.js")).hasValue(js.replace("0.9", "0.8"));
        assertThat(files.read(id, "copy.js")).hasValue(js);              // the old object is untouched
        // The zip
        byte[] zip = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/projects/" + id + "/files/download").header("Authorization", "Bearer " + a.token()))
                .andReturn().getResponse().getContentAsByteArray();
        try (var in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            List<String> names = new java.util.ArrayList<>();
            for (var e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                names.add(e.getName());
            }
            assertThat(names).anyMatch(n -> n.endsWith("/app.js")).anyMatch(n -> n.endsWith("/copy.js"));
        }
    }
}
