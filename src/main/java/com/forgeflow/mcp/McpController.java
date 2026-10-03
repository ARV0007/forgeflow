package com.forgeflow.mcp;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/mcp")
public class McpController {

    /** Pinned. See docs/notes.md for why this version and not the newest. */
    private static final String PROTOCOL_VERSION = "2025-06-18";

    @PostMapping
    public ResponseEntity<JsonRpc.Response> handle(@RequestBody JsonRpc.Request req) {

        // A notification has no id and expects no reply.
        if (req.id() == null) {
            return ResponseEntity.accepted().build();
        }

        JsonRpc.Response res = switch (req.method()) {
            case "initialize" -> JsonRpc.Response.ok(req.id(), initialize());
            default -> JsonRpc.Response.fail(req.id(), -32601, "Method not found: " + req.method());
        };

        return ResponseEntity.ok(res);
    }

    private Map<String, Object> initialize() {
        return Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of("tools", Map.of()),
                "serverInfo", Map.of("name", "forgeflow", "version", "0.0.1")
        );
    }
}