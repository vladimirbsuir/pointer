package dev.pointer.provider.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StableMapsTests {

    @Test
    void sortsUnorderedMapsAndKeepsInsertionOrder() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("path", Map.of("type", "string", "description", "File"));
        properties.put("limit", Map.of("type", "integer"));
        var schema = Map.<String, Object>of(
                "type", "object", "properties", properties, "required", List.of(Map.of("b", 1, "a", 2)));

        var copy = StableMaps.copy(schema);

        assertThat(copy.keySet()).containsExactly("properties", "required", "type");
        var copiedProperties = (Map<?, ?>) copy.get("properties");
        assertThat(keys(copiedProperties)).containsExactly("path", "limit");
        assertThat(keys(copiedProperties.get("path"))).containsExactly("description", "type");
        assertThat(keys(((List<?>) copy.get("required")).getFirst())).containsExactly("a", "b");
        assertThat(StableMaps.copy(copy)).isEqualTo(copy);
        assertThat(StableMaps.copy(copy).keySet()).containsExactly("properties", "required", "type");
    }

    @Test
    void toolDefinitionsSerializeTheSameWayWhateverMapTheyWereBuiltFrom() {
        var ordered = new LinkedHashMap<String, Object>();
        ordered.put("required", List.of());
        ordered.put("type", "object");

        var fromImmutable = new ChatRequest.ToolDefinition("t", "d", Map.of("type", "object", "required", List.of()));
        var fromOrdered = new ChatRequest.ToolDefinition("t", "d", ordered);

        assertThat(fromImmutable.parameters().keySet()).containsExactly("required", "type");
        assertThat(fromOrdered.parameters().keySet()).containsExactly("required", "type");
    }

    private static List<String> keys(Object map) {
        return ((Map<?, ?>) map).keySet().stream().map(String::valueOf).toList();
    }
}
