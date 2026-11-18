package dev.pointer.provider.llm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

public final class StableMaps {

    private StableMaps() {}

    public static Map<String, Object> copy(Map<String, Object> map) {
        return map == null ? null : copyMap(map);
    }

    private static Map<String, Object> copyMap(Map<?, ?> map) {
        var ordered = map instanceof LinkedHashMap<?, ?> || map instanceof SortedMap<?, ?>;
        var entries = new ArrayList<Map.Entry<?, ?>>(map.entrySet());
        if (!ordered) {
            entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));
        }
        var copy = new LinkedHashMap<String, Object>();
        entries.forEach(entry -> copy.put(String.valueOf(entry.getKey()), copyValue(entry.getValue())));
        return copy;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return copyMap(map);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(StableMaps::copyValue).toList();
        }
        return value;
    }
}
