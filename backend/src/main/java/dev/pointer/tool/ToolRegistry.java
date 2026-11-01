package dev.pointer.tool;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ToolRegistry {

    private final ToolRepository tools;

    ToolRegistry(ToolRepository tools) {
        this.tools = tools;
    }

    @Transactional(readOnly = true)
    public List<ToolDefinition> available(UUID userId, UUID projectId) {
        return tools.findAvailable(userId, projectId);
    }

    @Transactional(readOnly = true)
    public Optional<ToolDefinition> find(UUID userId, UUID projectId, String name) {
        return available(userId, projectId).stream()
                .filter(tool -> tool.getName().equals(name))
                .findFirst();
    }
}
