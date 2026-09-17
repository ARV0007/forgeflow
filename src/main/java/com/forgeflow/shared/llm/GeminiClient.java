package com.forgeflow.shared.llm;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
 * Talks to Google's Generative Language API over the JDK's own HttpClient.
 *
 * No SDK on purpose: one fewer dependency to version-match against Boot 4,
 * and the request/response shape stays visible instead of hidden behind a
 * framework abstraction.
 */
@Component
public class GeminiClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;
    private final String apiKey;
    private final String model;
    private final double temperature;

    public GeminiClient(@Value("${forgeflow.llm.api-key}") String apiKey,
                        @Value("${forgeflow.llm.model}") String model,
                        @Value("${forgeflow.llm.temperature}") double temperature,
                        @Value("${forgeflow.llm.timeout-seconds}") long timeoutSeconds) {
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public LlmResponse chat(String systemPrompt, List<LlmMessage> history, List<ToolSpec> tools) {
        try {
            String body = mapper.writeValueAsString(buildRequest(systemPrompt, history, tools));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE + model + ":generateContent"))
                    .header("x-goog-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new LlmException("Gemini returned " + response.statusCode() + ": " + response.body());
            }
            return parseResponse(mapper.readTree(response.body()));

        } catch (LlmException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted calling Gemini", e);
        } catch (Exception e) {
            throw new LlmException("Failed calling Gemini: " + e.getMessage(), e);
        }
    }

    private ObjectNode buildRequest(String systemPrompt, List<LlmMessage> history, List<ToolSpec> tools)
            throws Exception {

        ObjectNode root = mapper.createObjectNode();

        ObjectNode sys = root.putObject("systemInstruction");
        sys.putArray("parts").addObject().put("text", systemPrompt);

        ArrayNode contents = root.putArray("contents");
        for (LlmMessage m : history) {
            switch (m.role()) {
                case USER -> {
                    ObjectNode turn = contents.addObject();
                    turn.put("role", "user");
                    turn.putArray("parts").addObject().put("text", m.text());
                }
                case MODEL -> {
                    // Echoed verbatim so thoughtSignature survives the round trip.
                    ObjectNode turn = contents.addObject();
                    turn.put("role", "model");
                    turn.set("parts", mapper.readTree(m.rawModelParts()));
                }
                case TOOL_RESULTS -> {
                    ObjectNode turn = contents.addObject();
                    turn.put("role", "user");
                    ArrayNode parts = turn.putArray("parts");
                    for (ToolResult r : m.toolResults()) {
                        ObjectNode fr = parts.addObject().putObject("functionResponse");
                        fr.put("name", r.toolName());
                        fr.putObject("response").put("result", r.output());
                    }
                }
            }
        }

        if (tools != null && !tools.isEmpty()) {
            ArrayNode decls = root.putArray("tools").addObject().putArray("functionDeclarations");
            for (ToolSpec t : tools) {
                ObjectNode d = decls.addObject();
                d.put("name", t.name());
                d.put("description", t.description());
                d.set("parameters", mapper.valueToTree(t.parameters()));
            }
        }

        root.putObject("generationConfig").put("temperature", temperature);
        return root;
    }

    private LlmResponse parseResponse(JsonNode root) {
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            throw new LlmException("Gemini returned no candidates: " + root);
        }

        JsonNode content = candidates.get(0).path("content");
        JsonNode parts = content.path("parts");

        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();

        for (JsonNode part : parts) {
            if (part.has("text")) {
                text.append(part.get("text").asText());
            }
            if (part.has("functionCall")) {
                JsonNode fc = part.get("functionCall");
                Map<String, Object> args = new LinkedHashMap<>();
                JsonNode argsNode = fc.path("args");
                argsNode.propertyNames().forEach(
                        f -> args.put(f, argsNode.get(f).isTextual()
                                ? argsNode.get(f).asText()
                                : argsNode.get(f).toString()));
                calls.add(new ToolCall(fc.path("name").asText(), args));
            }
        }

        JsonNode usage = root.path("usageMetadata");
        // totalTokenCount is NOT prompt + completion: 2.5 Flash bills thinking
        // tokens on top. Use the total the API reports, not our own arithmetic.
        int prompt = usage.path("promptTokenCount").asInt(0);
        int completion = usage.path("candidatesTokenCount").asInt(0);
        int total = usage.path("totalTokenCount").asInt(prompt + completion);

        log.debug("Gemini: {} tool call(s), {} prompt + {} completion = {} total tokens",
                calls.size(), prompt, completion, total);

        return new LlmResponse(text.toString(), calls, parts.toString(), prompt, completion, total);
    }
}
