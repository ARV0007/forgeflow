package com.forgeflow.intelligence;

import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static com.forgeflow.support.ScriptedLlm.calls;
import static com.forgeflow.support.ScriptedLlm.finish;
import static com.forgeflow.support.ScriptedLlm.write;
import static org.assertj.core.api.Assertions.assertThat;

/** React projects: chosen at creation, built to a Vite layout, checked, and previewed through the in-browser runner. */
class ReactProjectTest extends ApiTestSupport {

    static final String PKG = """
            {"name":"app","type":"module","scripts":{"dev":"vite"},
             "dependencies":{"react":"^18.3.1","react-dom":"^18.3.1"},
             "devDependencies":{"vite":"^5.4.0","@vitejs/plugin-react":"^4.3.0"}}""";
    static final String HTML = """
            <!doctype html><html><head><title>t</title></head><body><div id="root"></div>
            <script type="module" src="/src/main.jsx"></script></body></html>""";
    static final String MAIN = """
            import { createRoot } from 'react-dom/client';
            import App from './App';
            createRoot(document.getElementById('root')).render(<App />);
            """;
    static final String APP = "export default function App() { return <h1>Don't stop</h1>; }\n";

    private long createReact(Account a, String name) {
        JsonNode p = post("/api/v1/projects", a, Map.of("name", name, "stack", "REACT"), 201);
        assertThat(p.path("stack").asText()).isEqualTo("REACT");
        return p.path("id").asLong();
    }

    private MvcResult raw(String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path)).andReturn();
    }

    @Test
    void aReactProjectGetsReactInstructionsAndAViteLayoutPassesItsBuild() {
        Account a = signup("reacter");
        long id = createReact(a, "kanban");
        assertThat(get("/api/v1/projects/" + id, a, 200).path("stack").asText()).isEqualTo("REACT");
        assertThat(post("/api/v1/projects", a, Map.of("name", "plain"), 201).path("stack").asText()).isEqualTo("STATIC");

        llm.then(calls(write("package.json", PKG), write("index.html", HTML), write("src/main.jsx", MAIN),
                        write("src/App.jsx", APP)))
           .then(calls(finish("Built a React app.")));
        JsonNode run = post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "A kanban board"), 200);

        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(llm.systemPrompts().get(0)).contains("You produce a React 18 app").contains("src/main.jsx");
    }

    @Test
    void theBuildGateCatchesAMissingComponentAndTheAgentRepairsIt() {
        Account a = signup("repairer");
        long id = createReact(a, "broken");
        llm.then(calls(write("package.json", PKG), write("index.html", HTML), write("src/main.jsx", MAIN)))
           .then(calls(finish("Done.")))                                  // forgot App.jsx
           .then(calls(write("src/App.jsx", APP)))
           .then(calls(finish("Done, with App.")));
        JsonNode run = post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "An app"), 200);

        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(run.path("repairRounds").asInt()).isEqualTo(1);
        String fedBack = llm.seen().get(2).get(llm.seen().get(2).size() - 1).toolResults().get(0).output();
        assertThat(fedBack).contains("src/main.jsx imports './App', but no such file exists");
    }

    @Test
    void thePreviewSwapsModuleScriptsForTheRunnerAndOpensTheSourcesToIt() throws Exception {
        Account a = signup("previewer");
        long id = createReact(a, "previewed");
        llm.then(calls(write("package.json", PKG), write("index.html", HTML), write("src/main.jsx", MAIN),
                        write("src/App.jsx", APP)))
           .then(calls(finish("Built.")));
        post("/api/v1/projects/" + id + "/generate", a, Map.of("prompt", "An app"), 200);
        String url = post("/api/v1/projects/" + id + "/preview", a, null, 200).path("url").asText();
        String token = url.split("/")[2];

        String html = raw(url).getResponse().getContentAsString();
        assertThat(html).doesNotContain("type=\"module\"")
                .contains("<script type=\"ff-module\" data-src=\"/src/main.jsx\"></script>")
                .contains("/p/" + token + "/__runner.js");
        assertThat(html.indexOf("__log")).isLessThan(html.indexOf("__runner.js"));   // console bridge first

        MvcResult source = raw("/p/" + token + "/src/App.jsx");
        assertThat(source.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo("*");
        assertThat(source.getResponse().getContentAsString()).isEqualTo(APP);

        JsonNode list = json.readTree(raw("/p/" + token + "/__files.json").getResponse().getContentAsString());
        assertThat(list).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("package.json", "index.html", "src/main.jsx", "src/App.jsx");

        assertThat(raw("/p/" + token + "/__runner.js").getResponse().getContentAsString())
                .startsWith("/*! ForgeFlow preview runner");
        assertThat(raw("/p/" + token + "/__vendor/react.js").getResponse().getContentAsString()).contains("React");
        assertThat(raw("/p/" + token + "/__vendor/react-dom.js").getResponse().getStatus()).isEqualTo(200);
        assertThat(raw("/p/" + token + "/__vendor/nothing.js").getResponse().getStatus()).isEqualTo(404);
        assertThat(raw("/p/not-a-token/__runner.js").getResponse().getStatus()).isEqualTo(404);
        assertThat(raw("/p/not-a-token/__files.json").getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void onlyTheRunWithNodePageIsCrossOriginIsolated() throws Exception {
        MvcResult run = raw("/run.html");
        assertThat(run.getResponse().getStatus()).isEqualTo(200);
        assertThat(run.getResponse().getHeader("Cross-Origin-Opener-Policy")).isEqualTo("same-origin");
        assertThat(run.getResponse().getHeader("Cross-Origin-Embedder-Policy")).isEqualTo("require-corp");
        assertThat(raw("/vendor/webcontainer-api.js").getResponse().getHeader("Cross-Origin-Embedder-Policy"))
                .isEqualTo("require-corp");
        assertThat(raw("/index.html").getResponse().getHeader("Cross-Origin-Embedder-Policy")).isNull();
    }
}
