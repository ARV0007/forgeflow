package com.forgeflow.execution;

import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Publish: a frozen version at a public address, that editing doesn't touch and rollback can move. */
class PublishTest extends ApiTestSupport {

    private String site(long id) {
        return "/api/v1/projects/" + id + "/site";
    }

    private void save(Account a, long id, String path, String content) {
        call(HttpMethod.PUT, "/api/v1/projects/" + id + "/files/content", a, Map.of("path", path, "content", content), 200);
    }

    private MvcResult visit(String url) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(url)).andReturn();      // no token: the public
    }

    @Test
    void aPublishedSiteIsFrozenUntilYouPublishAgainAndRollbackIsOneCall() throws Exception {
        Account a = signup("publisher");
        long id = createProject(a, "Habit Tracker!");
        save(a, id, "index.html", "<h1>Version one</h1>");

        JsonNode first = post(site(id), a, null, 200);
        String url = first.path("url").asText();
        assertThat(first.path("slug").asText()).matches("habit-tracker-[a-z2-9]{4}");
        assertThat(url).isEqualTo("/s/" + first.path("slug").asText() + "/");

        MvcResult page = visit(url);
        assertThat(page.getResponse().getStatus()).isEqualTo(200);
        assertThat(page.getResponse().getContentAsString()).isEqualTo("<h1>Version one</h1>");
        assertThat(page.getResponse().getHeader("Content-Security-Policy")).contains("sandbox").doesNotContain("allow-same-origin");
        assertThat(page.getResponse().getContentAsString()).doesNotContain("__log");      // no console bridge in public

        // Editing the project doesn't change the site...
        save(a, id, "index.html", "<h1>Version two</h1>");
        assertThat(visit(url + "index.html").getResponse().getContentAsString()).isEqualTo("<h1>Version one</h1>");

        // ...publishing does - at the same address.
        JsonNode second = post(site(id), a, null, 200);
        assertThat(second.path("url").asText()).isEqualTo(url);
        assertThat(visit(url).getResponse().getContentAsString()).isEqualTo("<h1>Version two</h1>");
        assertThat(second.path("releases")).hasSize(2);

        // Rollback: publish the first version again.
        post(site(id), a, Map.of("checkpointId", first.path("checkpointId").asLong()), 200);
        assertThat(visit(url).getResponse().getContentAsString()).isEqualTo("<h1>Version one</h1>");
        assertThat(get("/api/v1/projects/" + id + "/files/content?path=index.html", a, 200).path("content").asText())
                .isEqualTo("<h1>Version two</h1>");                                       // the project itself untouched

        // Unpublish: gone. Publish again: the same address comes back.
        call(HttpMethod.DELETE, site(id), a, null, 204);
        assertThat(visit(url).getResponse().getStatus()).isEqualTo(404);
        assertThat(get(site(id), a, 200).path("live").asBoolean()).isFalse();
        assertThat(post(site(id), a, null, 200).path("url").asText()).isEqualTo(url);
        assertThat(visit(url).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void reactSitesGetTheRunnerAndTheirFiles() throws Exception {
        Account a = signup("react-publisher");
        long id = post("/api/v1/projects", a, Map.of("name", "spa", "stack", "REACT"), 201).path("id").asLong();
        save(a, id, "package.json", "{\"dependencies\":{\"react\":\"^18.3.1\",\"react-dom\":\"^18.3.1\"}}");
        save(a, id, "index.html", "<html><head></head><body><div id=\"root\"></div>"
                + "<script type=\"module\" src=\"/src/main.jsx\"></script></body></html>");
        save(a, id, "src/main.jsx", "import { createRoot } from 'react-dom/client';\ncreateRoot(document.getElementById('root')).render(<h1>Hi</h1>);\n");
        String url = post(site(id), a, null, 200).path("url").asText();

        String html = visit(url).getResponse().getContentAsString();
        assertThat(html).contains(url + "__runner.js").contains("type=\"ff-module\"");
        assertThat(visit(url + "__files.json").getResponse().getContentAsString()).contains("src/main.jsx");
        assertThat(visit(url + "__runner.js").getResponse().getStatus()).isEqualTo(200);
        assertThat(visit(url + "src/main.jsx").getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo("*");
    }

    @Test
    void onlyEditorsPublishAndNothingLeaksAcrossProjects() throws Exception {
        Account owner = signup("site-owner");
        Account viewer = signup("site-viewer");
        Account stranger = signup("site-stranger");
        long id = createProject(owner, "mine");
        long other = createProject(owner, "other");
        save(owner, id, "index.html", "<p>x</p>");
        save(owner, other, "index.html", "<p>y</p>");
        post("/api/v1/projects/" + id + "/members", owner, Map.of("email", viewer.email(), "role", "VIEWER"), 201);
        long otherVersion = post(site(other), owner, null, 200).path("checkpointId").asLong();

        get(site(id), owner, 404);                                         // never published
        post(site(id), viewer, null, 403);
        post(site(id), stranger, null, 404);
        post(site(id), owner, Map.of("checkpointId", otherVersion), 404);  // someone else's version
        save(owner, id, "index.html", "");
        call(HttpMethod.PUT, "/api/v1/projects/" + id + "/files/content", owner,
                Map.of("path", "about.html", "content", "<p>about</p>"), 200);
        assertThat(visit("/s/no-such-site/").getResponse().getStatus()).isEqualTo(404);

        // A deleted project's site goes dark at once.
        String url = post(site(id), owner, null, 200).path("url").asText();
        call(HttpMethod.DELETE, "/api/v1/projects/" + id, owner, null, 204);
        assertThat(visit(url).getResponse().getStatus()).isEqualTo(404);
    }
}
