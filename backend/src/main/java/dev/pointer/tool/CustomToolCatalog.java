package dev.pointer.tool;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomToolCatalog {

    private final ToolRepository tools;
    private final SessionGrantRepository grants;
    private final Clock clock;

    @Transactional(readOnly = true)
    public boolean isNameReserved(UUID userId, String name) {
        return !tools.findNonCustomByName(userId, name).isEmpty();
    }

    @Transactional(readOnly = true)
    public List<ToolDefinition> visible(UUID userId, UUID projectId) {
        return tools.findCustomVisible(userId, projectId);
    }

    @Transactional(readOnly = true)
    public List<ToolDefinition> owned(UUID userId) {
        return tools.findCustom(userId);
    }

    @Transactional(readOnly = true)
    public Optional<ToolDefinition> find(UUID userId, UUID toolId) {
        return tools.findById(toolId)
                .filter(ToolDefinition::isCustom)
                .filter(tool -> userId.equals(tool.getOwnerUserId()));
    }

    @Transactional
    public ToolDefinition create(
            UUID userId, UUID projectId, String name, CustomToolSpec spec, String defaultPolicy, List<String> modes) {
        var now = clock.instant();
        var tool = ToolDefinition.custom(userId, projectId, name, spec, 1, now);
        tool.applyCustomSettings(defaultPolicy, modes, true, now);
        return tools.save(tool);
    }

    @Transactional
    public ToolDefinition applyVersion(UUID toolId, CustomToolSpec spec, int version) {
        var tool = tools.findById(toolId).orElseThrow();
        tool.applyCustomVersion(spec, version, clock.instant());
        grants.deleteByToolId(toolId);
        return tools.save(tool);
    }

    @Transactional
    public ToolDefinition applySettings(UUID toolId, String defaultPolicy, List<String> modes, boolean enabled) {
        var tool = tools.findById(toolId).orElseThrow();
        tool.applyCustomSettings(defaultPolicy, modes, enabled, clock.instant());
        return tools.save(tool);
    }

    @Transactional
    public void delete(UUID toolId) {
        tools.deleteById(toolId);
    }
}
