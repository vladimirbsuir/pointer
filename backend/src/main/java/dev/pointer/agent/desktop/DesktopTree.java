package dev.pointer.agent.desktop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DesktopTree {

    static final String HEADER = "Bounds are [x, y, width, height] in screenshot coordinates. Click an element with"
            + " desktop_click and its elementId.";
    static final String FALLBACK = "No window has the focus, so every window is listed.";
    static final String TRUNCATED = "The tree was cut at maxNodes, maxDepth or the time limit: raise maxNodes or"
            + " use scope focused to see the rest.";
    private static final String INDENT = "  ";

    private DesktopTree() {}

    static void toModelSpace(Map<String, Object> result, DesktopCoordinates coordinates) {
        var converted = new ArrayList<Map<String, Object>>();
        for (var node : nodes(result)) {
            var copy = new LinkedHashMap<>(node);
            if (hasBounds(node)) {
                copy.putAll(coordinates.modelRect(node));
            }
            converted.add(copy);
        }
        result.put("nodes", converted);
    }

    static String render(Map<String, Object> result, String requestedScope) {
        var lines = new ArrayList<String>();
        var nodes = nodes(result);
        if (!nodes.isEmpty()) {
            lines.add(HEADER);
            if ("focused".equals(requestedScope) && "all".equals(result.get("scope"))) {
                lines.add(FALLBACK);
            }
            nodes.forEach(node -> lines.add(line(node)));
        }
        if (Boolean.TRUE.equals(result.get("truncated"))) {
            lines.add(TRUNCATED);
        }
        if (result.get("note") instanceof String note && !note.isBlank()) {
            lines.add(note);
        }
        return String.join("\n", lines);
    }

    private static String line(Map<String, Object> node) {
        var depth = node.get("depth") instanceof Number number ? Math.max(0, number.intValue()) : 0;
        var line = new StringBuilder(INDENT.repeat(depth));
        line.append(node.get("id")).append(' ').append(node.get("role")).append(' ');
        line.append(quoted(node.get("name")));
        if (node.get("text") instanceof String text) {
            line.append(" text=").append(quoted(text));
        }
        if (node.get("value") != null) {
            line.append(" value=").append(node.get("value"));
        }
        if (hasBounds(node)) {
            line.append(" [")
                    .append(node.get("x"))
                    .append(", ")
                    .append(node.get("y"))
                    .append(", ")
                    .append(node.get("width"))
                    .append(", ")
                    .append(node.get("height"))
                    .append(']');
        }
        if (node.get("states") instanceof List<?> states && !states.isEmpty()) {
            line.append(" (")
                    .append(String.join(
                            ", ", states.stream().map(String::valueOf).toList()))
                    .append(')');
        }
        return line.toString();
    }

    private static String quoted(Object value) {
        var text = value == null ? "" : String.valueOf(value);
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static boolean hasBounds(Map<?, ?> node) {
        return node.get("x") instanceof Number
                && node.get("y") instanceof Number
                && node.get("width") instanceof Number
                && node.get("height") instanceof Number;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nodes(Map<String, Object> result) {
        if (!(result.get("nodes") instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(node -> node instanceof Map<?, ?>)
                .map(node -> (Map<String, Object>) node)
                .toList();
    }
}
