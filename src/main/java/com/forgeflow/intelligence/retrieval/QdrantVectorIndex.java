package com.forgeflow.intelligence.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Nearest-neighbour search in Qdrant, over its REST API - one collection for
 * every project, each point carrying {project_id, file_path, model} as
 * payload, and every query filtered by project and model. A payload index on
 * project_id makes that filter part of the HNSW search rather than a pass
 * afterwards - the weakness of the pgvector setup noted in the WORKLOG (a
 * busy project could crowd a small one out of the candidate list).
 *
 * The collection is created on first use, sized by the first vector seen.
 * Writes use wait=true: a search right after indexing must see the points.
 */
public class QdrantVectorIndex implements VectorIndex {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorIndex.class);

    private final String baseUrl;
    private final String collection;
    private final String apiKey;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private volatile boolean collectionReady;

    public QdrantVectorIndex(String baseUrl, String collection, String apiKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.collection = collection;
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
    }

    @Override
    public String name() {
        return "qdrant";
    }

    @Override
    public void replaceFile(Long projectId, String path, String model, List<Long> chunkIds, List<float[]> vectors) {
        if (!vectors.isEmpty()) {
            ensureCollection(vectors.get(0).length);
        } else if (!collectionReady) {
            return;            // nothing to delete in a collection that doesn't exist yet
        }
        removeFile(projectId, path);
        if (vectors.isEmpty()) {
            return;
        }
        ObjectNode body = json.createObjectNode();
        ArrayNode points = body.putArray("points");
        for (int i = 0; i < chunkIds.size(); i++) {
            ObjectNode p = points.addObject();
            p.put("id", chunkIds.get(i));
            ArrayNode v = p.putArray("vector");
            for (float f : vectors.get(i)) {
                v.add(f);
            }
            p.putObject("payload").put("project_id", projectId).put("file_path", path).put("model", model);
        }
        call("PUT", "/collections/" + collection + "/points?wait=true", body, 200);
    }

    @Override
    public void removeFile(Long projectId, String path) {
        if (!collectionReady && !collectionExists()) {
            return;
        }
        ObjectNode body = json.createObjectNode();
        ArrayNode must = body.putObject("filter").putArray("must");
        must.addObject().put("key", "project_id").putObject("match").put("value", projectId);
        must.addObject().put("key", "file_path").putObject("match").put("value", path);
        call("POST", "/collections/" + collection + "/points/delete?wait=true", body, 200);
    }

    @Override
    public List<Long> nearest(Long projectId, String model, float[] query, int n) {
        if (!collectionReady && !collectionExists()) {
            return List.of();
        }
        ObjectNode body = json.createObjectNode();
        ArrayNode v = body.putArray("vector");
        for (float f : query) {
            v.add(f);
        }
        body.put("limit", n);
        body.put("with_payload", false);
        ArrayNode must = body.putObject("filter").putArray("must");
        must.addObject().put("key", "project_id").putObject("match").put("value", projectId);
        must.addObject().put("key", "model").putObject("match").put("value", model);
        JsonNode r = call("POST", "/collections/" + collection + "/points/search", body, 200);
        List<Long> ids = new ArrayList<>();
        for (JsonNode hit : r.path("result")) {
            ids.add(hit.path("id").asLong());
        }
        return ids;
    }

    // ------------------------------------------------------------ helpers

    private boolean collectionExists() {
        HttpResponse<String> r = send("GET", "/collections/" + collection, null);
        collectionReady = r.statusCode() == 200;
        return collectionReady;
    }

    private synchronized void ensureCollection(int dimensions) {
        if (collectionReady || collectionExists()) {
            return;
        }
        ObjectNode body = json.createObjectNode();
        body.putObject("vectors").put("size", dimensions).put("distance", "Cosine");
        HttpResponse<String> r = send("PUT", "/collections/" + collection, body);
        if (r.statusCode() != 200 && !r.body().contains("already exists")) {
            throw new QdrantException("create collection " + collection, r);
        }
        // Filtering by project inside the HNSW search, not after it.
        for (Map.Entry<String, String> field : Map.of("project_id", "integer", "file_path", "keyword",
                "model", "keyword").entrySet()) {
            ObjectNode idx = json.createObjectNode().put("field_name", field.getKey()).put("field_schema", field.getValue());
            send("PUT", "/collections/" + collection + "/index?wait=true", idx);
        }
        collectionReady = true;
        log.info("created Qdrant collection {} ({} dims, cosine) at {}", collection, dimensions, baseUrl);
    }

    private JsonNode call(String method, String path, ObjectNode body, int expected) {
        HttpResponse<String> r = send(method, path, body);
        if (r.statusCode() != expected) {
            throw new QdrantException(method + " " + path, r);
        }
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, ObjectNode body) {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (apiKey != null) {
            req.header("api-key", apiKey);
        }
        try {
            return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new QdrantException(method + " " + path + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QdrantException(method + " " + path + " interrupted", e);
        }
    }

    public static class QdrantException extends RuntimeException {
        QdrantException(String what, HttpResponse<String> r) {
            super(what + " -> HTTP " + r.statusCode() + ": "
                    + (r.body().length() > 300 ? r.body().substring(0, 300) + "..." : r.body()));
        }

        QdrantException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
