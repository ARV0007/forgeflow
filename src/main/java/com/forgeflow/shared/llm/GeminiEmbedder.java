package com.forgeflow.shared.llm;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini's embedding model over plain HTTP, like GeminiClient.
 *
 * gemini-embedding-001 natively returns 3072 numbers. outputDimensionality
 * asks for 768 instead - the model is trained so a prefix of the vector is
 * itself a good embedding (Matryoshka representation learning), and 768 is
 * what the vector(768) column holds. Shorter vectors are a quarter of the
 * storage and faster to compare, for a small loss in quality.
 */
public class GeminiEmbedder implements Embedder {

    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final int MAX_BATCH = 100;      // the API's limit per batchEmbedContents call

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String apiKey;
    private final String model;

    public GeminiEmbedder(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String modelName() {
        return model + "@" + DIMENSIONS;
    }

    @Override
    public List<float[]> embed(List<String> texts, Kind kind) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += MAX_BATCH) {
            out.addAll(batch(texts.subList(from, Math.min(texts.size(), from + MAX_BATCH)), kind));
        }
        return out;
    }

    private List<float[]> batch(List<String> texts, Kind kind) {
        List<Map<String, Object>> requests = new ArrayList<>();
        for (String t : texts) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("model", "models/" + model);
            r.put("content", Map.of("parts", List.of(Map.of("text", t))));
            r.put("taskType", kind == Kind.QUERY ? "RETRIEVAL_QUERY" : "RETRIEVAL_DOCUMENT");
            r.put("outputDimensionality", DIMENSIONS);
            requests.add(r);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + model + ":batchEmbedContents"))
                .timeout(Duration.ofSeconds(30))
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("requests", requests))))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new LlmException("Embedding call failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted during embedding call");
        }
        if (response.statusCode() / 100 != 2) {
            throw new LlmException("Embedding call returned HTTP " + response.statusCode());
        }
        JsonNode embeddings = json.readTree(response.body()).path("embeddings");
        if (embeddings.size() != texts.size()) {
            throw new LlmException("Asked for " + texts.size() + " embeddings, got " + embeddings.size());
        }
        List<float[]> out = new ArrayList<>(texts.size());
        for (JsonNode e : embeddings) {
            JsonNode values = e.path("values");
            float[] v = new float[values.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) values.get(i).asDouble();
            }
            if (v.length != DIMENSIONS) {
                throw new LlmException("Expected " + DIMENSIONS + " dimensions, got " + v.length);
            }
            out.add(v);
        }
        return out;
    }
}
