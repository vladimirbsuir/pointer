package dev.pointer.tool;

import dev.pointer.execution.ExecutionService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ToolRegistry {

    private final ToolRepository tools;
    private final McpToolCatalog mcpTools;
    private final ExecutionService execution;

    @Transactional(readOnly = true)
    public List<ToolDefinition> available(UUID userId, UUID projectId, UUID deviceId) {
        var reported = mcpTools.reportedBy(deviceId);
        var interpreters = execution.interpreters(deviceId);
        return tools.findAvailable(userId, projectId).stream()
                .filter(tool -> !tool.isMcp() || reported.contains(tool.getName()))
                .filter(tool -> !tool.isCustom()
                        || interpreters
                                .map(found -> found.contains(tool.getInterpreter()))
                                .orElse(true))
                .filter(tool -> execution.isOperationAvailable(tool.getOperation(), projectId))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<ToolDefinition> find(UUID userId, UUID projectId, UUID deviceId, String name) {
        return available(userId, projectId, deviceId).stream()
                .filter(tool -> tool.getName().equals(name))
                .findFirst();
    }
}
