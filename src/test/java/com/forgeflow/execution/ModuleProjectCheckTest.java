package com.forgeflow.execution;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The React build check: what a model gets wrong in a multi-file app, caught without Node. */
class ModuleProjectCheckTest {

    static Map<String, String> app() {
        Map<String, String> f = new HashMap<>();
        f.put("package.json", """
                {"name":"x","type":"module","scripts":{"dev":"vite"},
                 "dependencies":{"react":"^18.3.1","react-dom":"^18.3.1"},
                 "devDependencies":{"vite":"^5.4.0","@vitejs/plugin-react":"^4.3.0"}}""");
        f.put("vite.config.js", "import react from '@vitejs/plugin-react';\nexport default { plugins: [react()] };\n");
        f.put("index.html", "<!doctype html><html><head></head><body><div id=\"root\"></div>\n"
                + "<script type=\"module\" src=\"/src/main.jsx\"></script></body></html>");
        f.put("src/main.jsx", """
                import { createRoot } from 'react-dom/client';
                import App from './App';
                import './index.css';
                createRoot(document.getElementById('root')).render(<App />);
                """);
        f.put("src/App.jsx", """
                import {
                  useState,
                } from 'react';
                import Card from './components/Card.jsx';
                export default function App() {
                  const [n, setN] = useState(0);
                  return (
                    <main>
                      <p>Don't panic - it's fine :) visit https://example.com (really</p>
                      <Card title={`Count ${n}`} onClick={() => setN(n + 1)} />
                    </main>
                  );
                }
                """);
        f.put("src/components/Card.jsx", "export default function Card({ title, onClick }) {\n"
                + "  return <button onClick={onClick}>{title}</button>;\n}\n");
        f.put("src/index.css", "body { margin: 0 }");
        return f;
    }

    @Test
    void aSoundProjectPassesEvenWithApostrophesAndUrlsAndSmileysInJsxText() {
        assertThat(ModuleProjectCheck.problems(app())).isEmpty();
    }

    @Test
    void aComponentImportedButNeverWrittenFails() {
        Map<String, String> f = app();
        f.remove("src/components/Card.jsx");
        assertThat(ModuleProjectCheck.problems(f))
                .containsExactly("src/App.jsx imports './components/Card.jsx', but no such file exists");
    }

    @Test
    void aPackageUsedButNotDeclaredFailsAndScopedSubpathsAreUnderstood() {
        Map<String, String> f = app();
        f.put("src/Icons.jsx", "import { Star } from 'lucide-react';\nimport x from '@vitejs/plugin-react/extra';\n"
                + "export const S = () => <Star />;\n");
        assertThat(ModuleProjectCheck.problems(f)).containsExactly(
                "src/Icons.jsx imports 'lucide-react', but \"lucide-react\" is not in package.json - add it to dependencies or don't use it");
    }

    @Test
    void packageJsonAndEntryProblemsAreNamed() {
        Map<String, String> f = app();
        f.put("package.json", "{\"dependencies\": {\"react\": \"18\"}");
        assertThat(ModuleProjectCheck.problems(f)).singleElement().asString().startsWith("package.json is not valid JSON");

        f = app();
        f.put("package.json", "{\"dependencies\": {\"react\": \"^18.3.1\"}, \"devDependencies\": {\"vite\": \"5\", \"@vitejs/plugin-react\": \"4\"}}");
        f.put("index.html", "<script type=\"module\" src=\"/src/index.jsx\"></script>");
        List<String> problems = ModuleProjectCheck.problems(f);
        assertThat(problems).contains("package.json: \"react-dom\" must be listed in dependencies",
                "index.html loads /src/index.jsx, which does not exist");
    }

    @Test
    void aTruncatedComponentFailsOnItsBraces() {
        Map<String, String> f = app();
        f.put("src/components/Card.jsx", "export default function Card({ title }) {\n  return <button>{title}</button>;\n");
        assertThat(ModuleProjectCheck.problems(f)).singleElement().asString()
                .startsWith("src/components/Card.jsx line 1: '{' is never closed");
    }
}
