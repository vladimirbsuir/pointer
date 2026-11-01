package dev.pointer.agent;

import dev.pointer.provider.llm.ChatRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PlanTool {

    static final String NAME = "propose_plan";
    static final String RESULT_TEXT =
            "The plan was shown to the user. Stop here and wait for the user to approve it or ask for changes.";

    static final ChatRequest.ToolDefinition DEFINITION = new ChatRequest.ToolDefinition(
            NAME,
            "Present the finished implementation plan to the user for approval. Call it once, after the investigation"
                    + " is complete. The user approves the plan or asks for changes.",
            Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of(
                            "summary",
                            Map.of("type", "string", "description", "One or two sentences describing the goal."),
                            "steps",
                            Map.of(
                                    "type",
                                    "array",
                                    "minItems",
                                    1,
                                    "items",
                                    Map.of(
                                            "type",
                                            "object",
                                            "properties",
                                            Map.of(
                                                    "title",
                                                    Map.of("type", "string", "description", "Short imperative step."),
                                                    "details",
                                                    Map.of(
                                                            "type",
                                                            "string",
                                                            "description",
                                                            "Files to change and how, in a few sentences.")),
                                            "required",
                                            List.of("title")))),
                    "required",
                    List.of("summary", "steps")));

    private PlanTool() {}

    static List<Map<String, Object>> steps(Map<String, Object> arguments) {
        if (!(arguments.get("steps") instanceof List<?> rawSteps)) {
            return List.of();
        }
        var steps = new ArrayList<Map<String, Object>>();
        for (var rawStep : rawSteps) {
            var step = new LinkedHashMap<String, Object>();
            if (rawStep instanceof Map<?, ?> map && map.get("title") instanceof String title && !title.isBlank()) {
                step.put("title", title.strip());
                if (map.get("details") instanceof String details && !details.isBlank()) {
                    step.put("details", details.strip());
                }
            } else if (rawStep instanceof String title && !title.isBlank()) {
                step.put("title", title.strip());
            } else {
                continue;
            }
            steps.add(step);
        }
        return steps;
    }

    static String summary(Map<String, Object> arguments) {
        return arguments.get("summary") instanceof String summary ? summary.strip() : "";
    }
}
