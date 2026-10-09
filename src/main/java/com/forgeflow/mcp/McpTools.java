package com.forgeflow.mcp;

import java.util.List;
import java.util.Map;

/**
 * The tool catalogue. These names and descriptions are read by the calling
 * model and are effectively part of its prompt, so they are written for a
 * reader who knows nothing about ForgeFlow.
 */
public final class McpTools {

    static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    /**
     * Declared as an integer, not a string. The services take a Long, and a
     * schema that says "string" gets you "43" with quotes around it.
     */
    static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required);
    }

    static Map<String, Object> tool(String name, String description, Map<String, Object> inputSchema) {
        return Map.of("name", name, "description", description, "inputSchema", inputSchema);
    }

    public static List<Map<String, Object>> all() {
        return List.of(
                tool("create_project",
                        "Create an empty ForgeFlow project. Returns a project_id needed by every "
                                + "other tool. Call this first unless you already have a project_id.",
                        schema(Map.of(
                                "name", str("Short name for the project, e.g. 'landing page'"),
                                "description", str("Optional one-line description of what it is for")
                        ), List.of("name"))),

                tool("generate_app",
                        "Build or modify a web app inside an existing ForgeFlow project. Takes a "
                                + "natural-language prompt describing what to build. Writes HTML, CSS and "
                                + "JavaScript, runs a build check, and repairs its own syntax errors before "
                                + "returning. Takes 5-20 seconds. Call repeatedly on the same project_id to "
                                + "iterate on what is already there.",
                        schema(Map.of(
                                "project_id", integer("The project to build into, from create_project"),
                                "prompt", str("What to build, in plain language")
                        ), List.of("project_id", "prompt"))),

                tool("list_project_files",
                        "List the files in a ForgeFlow project with their sizes. Use after "
                                + "generate_app to see what was produced.",
                        schema(Map.of(
                                "project_id", integer("The project to inspect")
                        ), List.of("project_id")))
        );
    }

    private McpTools() {}
}
