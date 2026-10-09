package com.forgeflow.shared.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * A stand-in model for running ForgeFlow without an API key
 * (FORGEFLOW_LLM_PROVIDER=demo).
 *
 * It doesn't understand anything. Asked for anything, it writes a small,
 * valid three-file page titled with your request, then calls finish. But it
 * does that through the REAL pipeline - tool calls, the path guard, the build
 * gate, chat memory, indexing, the logs stream, quotas - so the whole
 * workbench can be explored, demoed and browser-tested offline.
 *
 * Never selected unless asked for; the default provider is gemini.
 */
@Component
@ConditionalOnProperty(name = "forgeflow.llm.provider", havingValue = "demo")
public class DemoLlmClient implements LlmClient {

    @Override
    public String modelName() {
        return "demo";
    }

    @Override
    public LlmResponse chat(String systemPrompt, List<LlmMessage> history, List<ToolSpec> tools) {
        LlmMessage last = history.get(history.size() - 1);

        // A fresh request: write the files. Anything else (our own tool
        // results coming back): we're done.
        if (last.role() == LlmMessage.Role.USER) {
            String ask = firstLine(last.text());
            return new LlmResponse(null, List.of(
                    write("index.html", """
                            <!doctype html>
                            <html lang="en">
                            <head>
                              <meta charset="utf-8">
                              <meta name="viewport" content="width=device-width, initial-scale=1">
                              <title>%s</title>
                              <link rel="stylesheet" href="styles.css">
                            </head>
                            <body>
                              <main>
                                <p class="tag">Built by ForgeFlow's demo model</p>
                                <h1>%s</h1>
                                <p>The demo model writes this same starter page for any request. Set
                                   GOOGLE_API_KEY and use the gemini provider for the real thing.</p>
                                <button id="count">Clicked 0 times</button>
                              </main>
                              <script src="app.js"></script>
                            </body>
                            </html>
                            """.formatted(escape(ask), escape(ask))),
                    write("styles.css", """
                            body { margin: 0; font: 16px/1.5 system-ui, sans-serif; background: #faf7f2; color: #222; }
                            main { max-width: 40rem; margin: 4rem auto; padding: 0 1.5rem; }
                            .tag { color: #b5542a; font-size: .8rem; text-transform: uppercase; letter-spacing: .08em; }
                            button { font: inherit; padding: .5rem 1rem; border-radius: 6px; border: 1px solid #ccc; cursor: pointer; }
                            """),
                    write("app.js", """
                            let clicks = 0;
                            const button = document.getElementById('count');
                            button.addEventListener('click', () => {
                              clicks += 1;
                              button.textContent = 'Clicked ' + clicks + (clicks === 1 ? ' time' : ' times');
                              console.log('button clicked', clicks);
                            });
                            """)),
                    "[]", estimate(history), 400, estimate(history) + 400);
        }
        return new LlmResponse(null, List.of(new ToolCall("finish",
                Map.of("summary", "Built a starter page (demo model - it writes the same page for any request)."))),
                "[]", estimate(history), 30, estimate(history) + 30);
    }

    private static ToolCall write(String path, String content) {
        return new ToolCall("write_file", Map.of("path", path, "content", content));
    }

    private static String firstLine(String s) {
        String line = s == null ? "" : s.strip().split("\n", 2)[0];
        return line.length() > 80 ? line.substring(0, 77) + "..." : (line.isEmpty() ? "Your app" : line);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Roughly four characters per token, so the usage numbers look like usage numbers. */
    private static int estimate(List<LlmMessage> history) {
        int chars = 0;
        for (LlmMessage m : history) {
            chars += m.text() == null ? 0 : m.text().length();
        }
        return 800 + chars / 4;
    }
}
