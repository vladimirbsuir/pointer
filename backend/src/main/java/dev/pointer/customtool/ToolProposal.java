package dev.pointer.customtool;

import dev.pointer.tool.CustomToolSpec;
import java.util.Map;

public record ToolProposal(
        String name,
        String description,
        Map<String, Object> parametersSchema,
        String interpreter,
        String body,
        String scope) {

    CustomToolSpec spec() {
        return new CustomToolSpec(description, parametersSchema, interpreter);
    }
}
