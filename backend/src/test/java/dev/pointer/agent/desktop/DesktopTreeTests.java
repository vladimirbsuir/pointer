package dev.pointer.agent.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DesktopTreeTests {

    private static final DesktopGeometry LARGE = new DesktopGeometry(1920, 1200, 1280, 800);

    @Test
    void boundsComeBackInModelSpace() {
        var result = tree(List.of(node("e1", 0, "frame", "Notes", 0, 0, 1920, 1200)), "focused");

        DesktopTree.toModelSpace(result, new DesktopCoordinates(LARGE, false));
        assertThat(nodes(result).getFirst())
                .containsEntry("x", 0)
                .containsEntry("y", 0)
                .containsEntry("width", 1280)
                .containsEntry("height", 800);

        var grid = tree(List.of(node("e1", 0, "button", "Save", 960, 600, 192, 120)), "focused");
        DesktopTree.toModelSpace(grid, new DesktopCoordinates(LARGE, true));
        assertThat(nodes(grid).getFirst())
                .containsEntry("x", 500)
                .containsEntry("y", 500)
                .containsEntry("width", 100)
                .containsEntry("height", 100);
    }

    @Test
    void rendersOneIndentedLinePerElement() {
        var entry = node("e2", 1, "text", "Note \"draft\"", 10, 40, 300, 30);
        entry.put("text", "milk");
        entry.put("states", List.of("focused"));
        var spin = node("e3", 1, "spin button", "Font size", 10, 80, 100, 30);
        spin.put("value", 12);
        var result = tree(List.of(node("e1", 0, "frame", "Notes", 0, 0, 640, 480), entry, spin), "focused");

        assertThat(DesktopTree.render(result, "focused"))
                .isEqualTo(String.join(
                        "\n",
                        DesktopTree.HEADER,
                        "e1 frame \"Notes\" [0, 0, 640, 480]",
                        "  e2 text \"Note \\\"draft\\\"\" text=\"milk\" [10, 40, 300, 30] (focused)",
                        "  e3 spin button \"Font size\" value=12 [10, 80, 100, 30]"));
    }

    @Test
    void saysWhenTheFocusedScopeFellBackOrTheTreeWasCut() {
        var result = tree(List.of(node("e1", 0, "frame", "Notes", 0, 0, 640, 480)), "all");
        result.put("truncated", true);

        assertThat(DesktopTree.render(result, "focused").lines().toList())
                .containsExactly(
                        DesktopTree.HEADER,
                        DesktopTree.FALLBACK,
                        "e1 frame \"Notes\" [0, 0, 640, 480]",
                        DesktopTree.TRUNCATED);
        assertThat(DesktopTree.render(result, "all")).doesNotContain(DesktopTree.FALLBACK);
    }

    @Test
    void anEmptyTreeRendersItsNoteOnly() {
        var result = tree(List.of(), "all");
        result.put("note", "No application on the display exposes an accessibility tree.");

        assertThat(DesktopTree.render(result, "focused"))
                .isEqualTo("No application on the display exposes an accessibility tree.");
    }

    private static Map<String, Object> tree(List<Map<String, Object>> nodes, String scope) {
        var result = new LinkedHashMap<String, Object>();
        result.put("scope", scope);
        result.put("nodes", nodes);
        result.put("truncated", false);
        return result;
    }

    private static Map<String, Object> node(
            String id, int depth, String role, String name, int x, int y, int width, int height) {
        var node = new LinkedHashMap<String, Object>();
        node.put("id", id);
        node.put("depth", depth);
        node.put("role", role);
        node.put("name", name);
        node.put("x", x);
        node.put("y", y);
        node.put("width", width);
        node.put("height", height);
        return node;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nodes(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("nodes");
    }
}
