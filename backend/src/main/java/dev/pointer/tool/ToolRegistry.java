package dev.pointer.tool;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ToolRegistry {

    private final ToolRepository tools;
    private final McpToolCatalog mcpTools;

    ToolRegistry(ToolRepository tools, McpToolCatalog mcpTools) {
        this.tools = tools;
        this.mcpTools = mcpTools;
    }

    @Transactional(readOnly = true)
    public List<ToolDefinition> available(UUID userId, UUID projectId, UUID deviceId) {
        var reported = mcpTools.reportedBy(deviceId);
        return tools.findAvailable(userId, projectId).stream()
                .filter(tool -> !tool.isMcp() || reported.contains(tool.getName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<ToolDefinition> find(UUID userId, UUID projectId, UUID deviceId, String name) {
        return available(userId, projectId, deviceId).stream()
                .filter(tool -> tool.getName().equals(name))
                .findFirst();
    }
}
