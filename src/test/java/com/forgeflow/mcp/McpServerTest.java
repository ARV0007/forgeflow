package com.forgeflow.mcp;

import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP server over HTTP, as a client sees it - JSON-RPC envelopes and all.
 * Nothing here calls the model; generate_app is exercised only on its access
 * check, which runs before the model is ever asked.
 */
class McpServerTest extends ApiTestSupport {

    private JsonNode rpc(Object id, String method, Map<String, Object> params) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        if (id != null) {
            body.put("id", id);
        }
        body.put("method", method);
        if (params != null) {
            body.put("params", params);
        }
        return post("/mcp", null, body, id == null ? 202 : 200);
    }

    private JsonNode toolCall(String name, Map<String, Object> args) {
        return rpc(7, "tools/call", Map.of("name", name, "arguments", args)).path("result");
    }

    @Test
    void handshakePinsTheProtocolVersion() {
        JsonNode res = rpc(1, "initialize", Map.of("protocolVersion", "2025-06-18",
                "capabilities", Map.of(), "clientInfo", Map.of("name", "test", "version", "1")));
        assertThat(res.path("result").path("protocolVersion").asText()).isEqualTo("2025-06-18");
        assertThat(res.has("error")).as("a success carries no error field at all").isFalse();
    }

    @Test
    void notificationsGetNoBody() {
        assertThat(rpc(null, "notifications/initialized", null)).isNull();
    }

    @Test
    void toolListDeclaresProjectIdAsAnInteger() {
        JsonNode tools = rpc(2, "tools/list", null).path("result").path("tools");
        assertThat(tools).hasSize(3);
        for (JsonNode t : tools) {
            JsonNode pid = t.path("inputSchema").path("properties").path("project_id");
            if (!pid.isMissingNode()) {
                assertThat(pid.path("type").asText()).as(t.path("name").asText()).isEqualTo("integer");
            }
        }
    }

    @Test
    void unknownMethodIsAProtocolErrorButABadProjectIsAToolError() {
        JsonNode unknown = rpc(3, "nonsense", null);
        assertThat(unknown.path("error").path("code").asInt()).isEqualTo(-32601);
        assertThat(unknown.has("result")).isFalse();

        JsonNode badProject = toolCall("list_project_files", Map.of("project_id", Long.MAX_VALUE));
        assertThat(badProject.path("isError").asBoolean()).isTrue();
    }

    /**
     * Regression test for a real hole: generate_app reached AgentService, which
     * does no access check of its own, so an MCP caller could write into any
     * project id it could guess. It must now be refused before the model runs.
     */
    @Test
    void generateAppRefusesAProjectTheServiceAccountCannotWriteTo() {
        Account owner = signup("someone");
        long someoneElses = createProject(owner, "private");

        JsonNode result = toolCall("generate_app", Map.of("project_id", someoneElses, "prompt", "overwrite it"));

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asText()).contains("Project not found");
        // Nothing was generated into it.
        assertThat(get("/api/v1/projects/" + someoneElses + "/files", owner, 200)).isEmpty();
    }

    @Test
    void createThenListRoundTrips() {
        JsonNode created = toolCall("create_project", Map.of("name", "via-mcp"));
        String text = created.path("content").get(0).path("text").asText();
        assertThat(created.path("isError").asBoolean()).isFalse();

        long id = Long.parseLong(text.replaceAll("^Created project (\\d+).*$", "$1").split("\\s")[0]);
        JsonNode listed = toolCall("list_project_files", Map.of("project_id", id));
        assertThat(listed.path("content").get(0).path("text").asText()).contains("no files yet");
    }

    @Test
    void unknownToolIsAToolError() {
        JsonNode r = toolCall("no_such_tool", Map.of());
        assertThat(r.path("isError").asBoolean()).isTrue();
        assertThat(r.path("content")).isInstanceOf(JsonNode.class);
        assertThat(List.of(r.path("content").get(0).path("type").asText())).containsExactly("text");
    }
}
