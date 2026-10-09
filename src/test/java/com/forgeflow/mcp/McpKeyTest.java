package com.forgeflow.mcp;

import com.forgeflow.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;

/** With FORGEFLOW_MCP_API_KEY set, /mcp answers only callers that present it. */
@TestPropertySource(properties = "forgeflow.mcp.api-key=mcp-test-key-123")
class McpKeyTest extends ApiTestSupport {

    private static final String LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";

    private MockHttpServletResponse call(String header, String value, String body) throws Exception {
        var req = MockMvcRequestBuilders.post("/mcp").contentType(MediaType.APPLICATION_JSON).content(body);
        if (header != null) {
            req.header(header, value);
        }
        return mvc.perform(req).andReturn().getResponse();
    }

    @Test
    void theKeyIsRequiredAndEitherHeaderWorks() throws Exception {
        assertThat(call(null, null, LIST).getStatus()).isEqualTo(401);
        assertThat(call(null, null, LIST).getHeader("WWW-Authenticate")).startsWith("Bearer");
        assertThat(call("Authorization", "Bearer wrong", LIST).getStatus()).isEqualTo(401);
        assertThat(call("X-API-Key", "mcp-test-key-12", LIST).getStatus()).isEqualTo(401);   // a prefix is not the key

        assertThat(call("Authorization", "Bearer mcp-test-key-123", LIST).getContentAsString()).contains("generate_app");
        assertThat(call("X-API-Key", "mcp-test-key-123", LIST).getContentAsString()).contains("generate_app");
    }

    @Test
    void evenNotificationsNeedIt() throws Exception {
        String note = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
        assertThat(call(null, null, note).getStatus()).isEqualTo(401);
        assertThat(call("X-API-Key", "mcp-test-key-123", note).getStatus()).isEqualTo(202);
    }
}
