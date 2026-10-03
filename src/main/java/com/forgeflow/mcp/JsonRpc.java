package com.forgeflow.mcp;

import tools.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonInclude;
/** JSON-RPC 2.0 envelope types. MCP is JSON-RPC over HTTP, nothing more. */
public final class JsonRpc {

    /** params stays a raw JsonNode: each method shape is different, so we parse it per-method. */
    public record Request(String jsonrpc, Object id, String method, JsonNode params) {}

    public record Error(int code, String message) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(String jsonrpc, Object id, Object result, Error error) {
        public static Response ok(Object id, Object result) {
            return new Response("2.0", id, result, null);
        }
        public static Response fail(Object id, int code, String message) {
            return new Response("2.0", id, null, new Error(code, message));
        }
    }

    private JsonRpc() {}
}