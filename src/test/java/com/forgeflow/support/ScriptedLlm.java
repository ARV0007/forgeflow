package com.forgeflow.support;

import com.forgeflow.shared.llm.LlmClient;
import com.forgeflow.shared.llm.LlmException;
import com.forgeflow.shared.llm.LlmMessage;
import com.forgeflow.shared.llm.LlmResponse;
import com.forgeflow.shared.llm.ToolCall;
import com.forgeflow.shared.llm.ToolSpec;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A model that says exactly what the test tells it to, in order.
 *
 * This is what makes the agent loop testable at all. The real model is slow,
 * costs quota, and never answers the same way twice; this one is instant,
 * free and deterministic. It also records the history it was shown on every
 * call, so a test can assert on what the model SAW - conversation memory, the
 * build errors fed back by the self-healing gate - not just on what came out.
 *
 * An empty script fails the call, which is exactly how an unreachable or
 * broken provider behaves.
 */
public class ScriptedLlm implements LlmClient {

    private record Step(LlmResponse response, long backoffMillis, String failure) {
    }

    private final ConcurrentLinkedDeque<Step> script = new ConcurrentLinkedDeque<>();
    private final List<List<LlmMessage>> seen = new CopyOnWriteArrayList<>();
    private final List<String> systemPrompts = new CopyOnWriteArrayList<>();

    public ScriptedLlm then(LlmResponse response) {
        script.add(new Step(response, 0, null));
        return this;
    }

    /** This answer comes only after waiting out a (simulated) rate limit, as GeminiClient would. */
    public ScriptedLlm thenAfterRateLimit(long waitMillis, LlmResponse response) {
        script.add(new Step(response, waitMillis, null));
        return this;
    }

    /** This call fails the way a provider does once its own retries are spent (a 503, a retired model). */
    public ScriptedLlm thenFail(String message) {
        script.add(new Step(null, 0, message));
        return this;
    }

    public void reset() {
        script.clear();
        seen.clear();
        systemPrompts.clear();
        models.clear();
    }

    private final List<String> models = new CopyOnWriteArrayList<>();

    /** The model named on each call, in call order ("scripted" when none was named). */
    public List<String> models() {
        return models;
    }

    /** Like Gemini, it takes model names - so routing and fallback can be asserted on. */
    @Override
    public boolean namesModels() {
        return true;
    }

    @Override
    public LlmResponse chat(String model, String systemPrompt, List<LlmMessage> history, List<ToolSpec> tools) {
        return answer(model, systemPrompt, history);
    }

    /** The system prompt passed on each call, in call order. */
    public List<String> systemPrompts() {
        return systemPrompts;
    }

    /** The history passed on each call, in call order. */
    public List<List<LlmMessage>> seen() {
        return seen;
    }

    @Override
    public LlmResponse chat(String systemPrompt, List<LlmMessage> history, List<ToolSpec> tools) {
        return answer("scripted", systemPrompt, history);
    }

    private LlmResponse answer(String model, String systemPrompt, List<LlmMessage> history) {
        seen.add(List.copyOf(history));
        systemPrompts.add(systemPrompt);
        models.add(model);
        Step next = script.poll();
        if (next == null) {
            throw new LlmException("ScriptedLlm: nothing left in the script");
        }
        if (next.failure() != null) {
            throw new LlmException(next.failure());
        }
        if (next.backoffMillis() > 0) {
            com.forgeflow.shared.llm.Backoffs.simulate(next.backoffMillis());
        }
        return next.response();
    }

    @Override
    public String modelName() {
        return "scripted";
    }

    // ------------------------------------------------------------ builders

    public static LlmResponse calls(ToolCall... calls) {
        return new LlmResponse(null, Arrays.asList(calls), "[]", 100, 20, 120);
    }

    public static LlmResponse text(String text) {
        return new LlmResponse(text, List.of(), "[]", 100, 20, 120);
    }

    public static ToolCall write(String path, String content) {
        return new ToolCall("write_file", Map.of("path", path, "content", content));
    }

    public static ToolCall read(String path) {
        return new ToolCall("read_file", Map.of("path", path));
    }

    public static ToolCall finish(String summary) {
        return new ToolCall("finish", Map.of("summary", summary));
    }

    // A small, structurally valid site.
    public static final String INDEX = """
            <!doctype html><html><head><link rel="stylesheet" href="styles.css"></head>
            <body><h1>Hello</h1><script src="app.js"></script></body></html>""";
    public static final String CSS = "h1 { color: navy; }";
    public static final String JS = "document.querySelector('h1').addEventListener('click', () => alert('hi'));";
    /** Fails the structural check: unbalanced ( and {. */
    public static final String BROKEN_JS = "function broken( { return";
}
