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

        // The visual reviewer (no tools, a screenshot attached). The demo can't
        // see, so it plays the loop honestly labelled: the first look at a
        // change flags one issue, the look after the fix passes - which is
        // enough to walk the whole review -> fix -> re-review path offline.
        if (systemPrompt != null && systemPrompt.startsWith("You review a screenshot")) {
            boolean afterFix = last.text() != null && last.text().contains("LATEST REQUEST: Visual check");
            String review = afterFix
                    ? "{\"score\": 9, \"verdict\": \"looks_right\", \"summary\": \"Demo review: the fix is in and the page reads well.\", \"issues\": []}"
                    : "{\"score\": 6, \"verdict\": \"needs_fixes\", \"summary\": \"Demo review: the demo model always finds one thing on a first look.\", "
                      + "\"issues\": [{\"severity\": \"major\", \"text\": \"The counter button blends into the page; give it a solid background colour.\"}, "
                      + "{\"severity\": \"minor\", \"text\": \"The heading could use more space above it.\"}]}";
            return new LlmResponse(review, List.of(), "[]", estimate(history) + 258, 60, estimate(history) + 318);
        }

        // A fresh request: write the files. Anything else (our own tool
        // results coming back): we're done.
        if (last.role() == LlmMessage.Role.USER && systemPrompt != null && systemPrompt.contains("You produce a React")) {
            return reactApp(firstLine(last.text()), history);
        }
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

    /** The React stack's starter: a real Vite layout, two components, a hook - so the runner has work to do. */
    private LlmResponse reactApp(String ask, List<LlmMessage> history) {
        String title = ask.replace("\\", "\\\\").replace("'", "\\'");
        return new LlmResponse(null, List.of(
                write("package.json", """
                        {
                          "name": "forgeflow-app",
                          "private": true,
                          "version": "0.1.0",
                          "type": "module",
                          "scripts": { "dev": "vite", "build": "vite build", "preview": "vite preview" },
                          "dependencies": { "react": "^18.3.1", "react-dom": "^18.3.1" },
                          "devDependencies": { "vite": "^5.4.0", "@vitejs/plugin-react": "^4.3.0" }
                        }
                        """),
                write("vite.config.js", """
                        import { defineConfig } from 'vite';
                        import react from '@vitejs/plugin-react';

                        export default defineConfig({ plugins: [react()] });
                        """),
                write("index.html", """
                        <!doctype html>
                        <html lang="en">
                          <head>
                            <meta charset="UTF-8" />
                            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                            <title>%s</title>
                          </head>
                          <body>
                            <div id="root"></div>
                            <script type="module" src="/src/main.jsx"></script>
                          </body>
                        </html>
                        """.formatted(escape(ask))),
                write("src/main.jsx", """
                        import { StrictMode } from 'react';
                        import { createRoot } from 'react-dom/client';
                        import App from './App.jsx';
                        import './index.css';

                        createRoot(document.getElementById('root')).render(
                          <StrictMode>
                            <App />
                          </StrictMode>
                        );
                        """),
                write("src/App.jsx", """
                        import Counter from './components/Counter';

                        export default function App() {
                          return (
                            <main>
                              <p className="tag">Built by ForgeFlow's demo model &middot; React</p>
                              <h1>{'%s'}</h1>
                              <p>The demo model writes this same starter for any request. It's a real
                                 Vite + React project: it runs here, in a WebContainer, or after npm install.</p>
                              <Counter />
                            </main>
                          );
                        }
                        """.formatted(title)),
                write("src/components/Counter.jsx", """
                        import { useState } from 'react';

                        export default function Counter() {
                          const [clicks, setClicks] = useState(0);
                          const click = () => {
                            const next = clicks + 1;
                            setClicks(next);
                            console.log('react button clicked', next);
                          };
                          return (
                            <button id="count" onClick={click}>
                              Clicked {clicks} {clicks === 1 ? 'time' : 'times'}
                            </button>
                          );
                        }
                        """),
                write("src/index.css", """
                        body { margin: 0; font: 16px/1.5 system-ui, sans-serif; background: #f4f7fb; color: #1d2433; }
                        main { max-width: 40rem; margin: 4rem auto; padding: 0 1.5rem; }
                        .tag { color: #2a6fb5; font-size: .8rem; text-transform: uppercase; letter-spacing: .08em; }
                        button { font: inherit; padding: .5rem 1rem; border-radius: 6px; border: 1px solid #b8c4d6; background: #fff; cursor: pointer; }
                        """)),
                "[]", estimate(history), 700, estimate(history) + 700);
    }

    private static ToolCall write(String path, String content) {
        return new ToolCall("write_file", Map.of("path", path, "content", content));
    }

    private static String firstLine(String s) {
        String text = s == null ? "" : s.strip();
        if (text.startsWith("ATTACHED:") && text.contains("\n\n")) {
            text = text.substring(text.indexOf("\n\n") + 2).strip();     // skip the image brief: title the page with the ask
        }
        String line = text.split("\n", 2)[0];
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
