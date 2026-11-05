package dev.pointer.customtool;

import dev.pointer.provider.llm.ChatRequest;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ProposeTool {

    public static final String NAME = "propose_tool";
    static final List<String> INTERPRETERS = List.of("python", "node", "powershell", "bash");

    private static final String DESCRIPTION = """
            Propose a reusable custom tool, or a new version of an existing custom tool, when a task needs the same \
            multi-step work repeatedly or a capability the other tools lack. The user reviews the script and approves \
            or rejects it. An approved tool can be called from the next step on and stays available in later \
            sessions. The script runs on the user's computer with the project root as the working directory. It \
            receives the arguments as a JSON object on stdin and must print its result to stdout; a non-zero exit \
            code is reported as an error together with stderr. Keep scripts short, self-contained and \
            non-interactive, and use only the standard library of the interpreter. Proposing an existing custom \
            tool name proposes a new version of that tool.""";

    private ProposeTool() {}

    public static ChatRequest.ToolDefinition definition(Collection<String> interpreters) {
        var available = INTERPRETERS.stream().filter(interpreters::contains).toList();
        var properties = new LinkedHashMap<String, Object>();
        properties.put(
                "name",
                Map.of(
                        "type",
                        "string",
                        "pattern",
                        CustomToolService.NAME_PATTERN,
                        "description",
                        "Tool name in snake_case, 3 to 48 characters."));
        properties.put(
                "description",
                Map.of("type", "string", "description", "What the tool does and when to use it, for the model."));
        properties.put(
                "parameters",
                Map.of("type", "object", "description", "JSON Schema of the arguments, with type object."));
        properties.put("interpreter", Map.of("type", "string", "enum", available));
        properties.put("body", Map.of("type", "string", "description", "The complete script."));
        properties.put(
                "scope",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of("project", "user"),
                        "description",
                        "project for tools specific to this project, user for tools useful in any project."));
        return new ChatRequest.ToolDefinition(
                NAME,
                DESCRIPTION + " Available interpreters: " + String.join(", ", available) + ".",
                Map.of(
                        "type",
                        "object",
                        "properties",
                        properties,
                        "required",
                        List.of("name", "description", "parameters", "interpreter", "body")));
    }
}
