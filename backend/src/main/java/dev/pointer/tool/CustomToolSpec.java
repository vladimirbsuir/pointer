package dev.pointer.tool;

import java.util.Map;

public record CustomToolSpec(String description, Map<String, Object> parametersSchema, String interpreter) {}
