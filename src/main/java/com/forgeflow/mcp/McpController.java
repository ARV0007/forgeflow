package com.forgeflow.mcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestHeader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The whole MCP surface: one POST endpoint speaking JSON-RPC 2.0.
 *
 * Pinned to one protocol version deliberately. The spec has moved five times
 * since 2024 and 2026-07-28 drops the initialize handshake entirely, but
 * Anthropic's clients still perform it and the newer spec tells clients to
 * treat older servers as valid. Tracking the spec is not this project's job.
 */
@RestController
@RequestMapping("/mcp")
public class McpController {

    /** Pinned. See docs/notes.md for why this version and not the newest. */
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final McpToolExecutor tools;
    private final byte[] apiKey;

    /**
     * @param apiKey FORGEFLOW_MCP_API_KEY. Blank keeps /mcp open, which is fine
     *               on a laptop. Set, every call must present it - as
     *               "Authorization: Bearer <key>" (what Claude Code's
     *               --header sends) or "X-API-Key: <key>".
     */
    public McpController(McpToolExecutor tools, @Value("${forgeflow.mcp.api-key:}") String apiKey) {
        this.tools = tools;
        this.apiKey = apiKey.isBlank() ? null : apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping
    public ResponseEntity<JsonRpc.Response> handle(@RequestBody JsonRpc.Request req,
                                                   @RequestHeader(value = "Authorization", required = false) String authorization,
                                                   @RequestHeader(value = "X-API-Key", required = false) String xApiKey) {
        // Checked before anything else - including notifications - so an
        // unauthenticated caller learns nothing, not even which methods exist.
        if (apiKey != null && !presented(authorization, xApiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"forgeflow-mcp\"")
                    .build();
        }

        // A notification has no id and expects no reply. MCP sends
        // notifications/initialized right after the handshake; answering it
        // confuses clients that are not waiting for a response.
        if (req.id() == null) {
            return ResponseEntity.accepted().build();
        }

        JsonRpc.Response res = switch (req.method()) {
            case "initialize" -> JsonRpc.Response.ok(req.id(), initialize());
            case "tools/list" -> JsonRpc.Response.ok(req.id(), Map.of("tools", McpTools.all()));
            case "tools/call" -> JsonRpc.Response.ok(req.id(),
                    tools.call(req.params().path("name").asText(),
                               req.params().path("arguments")));
            default -> JsonRpc.Response.fail(req.id(), -32601, "Method not found: " + req.method());
        };

        return ResponseEntity.ok(res);
    }

    private boolean presented(String authorization, String xApiKey) {
        String given = xApiKey;
        if (given == null && authorization != null && authorization.startsWith("Bearer ")) {
            given = authorization.substring(7).trim();
        }
        // Constant time, so response timing doesn't reveal how much of a guess was right.
        return given != null && MessageDigest.isEqual(apiKey, given.getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> initialize() {
        return Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of("tools", Map.of()),
                "serverInfo", Map.of("name", "forgeflow", "version", "0.0.1")
        );
    }
}
