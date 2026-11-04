package dev.pointer.tool;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class McpToolCatalog {

    private static final int MAX_TOOL_NAME_LENGTH = 64;

    private final ToolRepository tools;
    private final Clock clock;
    private final Map<UUID, Set<String>> reportedByDevice = new ConcurrentHashMap<>();

    @Transactional
    public void report(UUID userId, UUID deviceId, List<ReportedTool> reported) {
        var existing =
                tools.findMcp(userId).stream().collect(Collectors.toMap(ToolDefinition::getName, Function.identity()));
        var names = new HashSet<String>();
        var now = clock.instant();
        for (var tool : reported) {
            var name = toolName(tool.server(), tool.name());
            if (!names.add(name)) {
                continue;
            }
            var definition = existing.get(name);
            if (definition == null) {
                tools.save(ToolDefinition.mcp(
                        userId,
                        name,
                        tool.server(),
                        tool.name(),
                        tool.description(),
                        tool.inputSchema(),
                        tool.readOnly(),
                        now));
            } else {
                definition.refreshMcp(tool.description(), tool.inputSchema(), tool.readOnly(), now);
                tools.save(definition);
            }
        }
        reportedByDevice.put(deviceId, Set.copyOf(names));
    }

    Set<String> reportedBy(UUID deviceId) {
        return deviceId == null ? Set.of() : reportedByDevice.getOrDefault(deviceId, Set.of());
    }

    static String toolName(String server, String tool) {
        var name = "mcp__" + server + "__" + tool.replaceAll("[^A-Za-z0-9_-]", "_");
        return name.length() > MAX_TOOL_NAME_LENGTH ? name.substring(0, MAX_TOOL_NAME_LENGTH) : name;
    }

    public record ReportedTool(
            String server, String name, String description, Map<String, Object> inputSchema, boolean readOnly) {}
}
