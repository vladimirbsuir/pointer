package dev.pointer.customtool;

import dev.pointer.common.error.ApiException;
import dev.pointer.session.ApprovalService;
import dev.pointer.tool.CustomToolCatalog;
import dev.pointer.tool.ToolDefinition;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class CustomToolService {

    static final String NAME_PATTERN = "^[a-z][a-z0-9_]{2,47}$";
    static final String USER = "user";
    static final String PROJECT = "project";
    private static final int MAX_DESCRIPTION_CHARS = 2_000;
    private static final int MAX_BODY_CHARS = 65_536;
    private static final int MAX_SCHEMA_CHARS = 16_384;
    private static final String DEFAULT_POLICY = "ask";
    private static final String AGENT = "agent";
    private static final List<String> MODE_ORDER = List.of("ask", "plan", AGENT);
    private static final Set<String> RESERVED_NAMES = Set.of(ProposeTool.NAME, "propose_plan");

    private final CustomToolCatalog catalog;
    private final CustomToolVersionRepository versions;
    private final JsonMapper json;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PreparedProposal prepare(UUID userId, UUID projectId, Map<String, Object> arguments) {
        var proposal = parse(arguments);
        if (RESERVED_NAMES.contains(proposal.name()) || catalog.isNameReserved(userId, proposal.name())) {
            throw new InvalidProposalException(
                    "The name " + proposal.name() + " is used by a built-in or MCP tool. Choose another name.");
        }
        var existing = catalog.visible(userId, projectId).stream()
                .filter(tool -> tool.getName().equals(proposal.name()))
                .findFirst();
        if (existing.isPresent()) {
            var tool = existing.get();
            var current = currentVersion(tool);
            if (current.sameAs(proposal)) {
                throw new InvalidProposalException(
                        "The proposal is identical to the current version of " + proposal.name() + ".");
            }
            return new PreparedProposal(
                    proposal,
                    tool.getId(),
                    scopeOf(tool),
                    versions.latestVersion(tool.getId()) + 1,
                    false,
                    tool.getDefaultPolicy(),
                    tool.getModes(),
                    previous(current));
        }
        var userScopeAvailable =
                catalog.owned(userId).stream().noneMatch(tool -> tool.getName().equals(proposal.name()));
        var scope = userScopeAvailable ? proposal.scope() : PROJECT;
        return new PreparedProposal(proposal, null, scope, 1, userScopeAvailable, DEFAULT_POLICY, List.of(AGENT), null);
    }

    @Transactional
    public ApprovedTool approve(
            UUID userId,
            UUID projectId,
            UUID sessionId,
            UUID itemId,
            PreparedProposal prepared,
            ApprovalService.ToolOptions options) {
        var proposal = prepared.proposal();
        var policy =
                options == null || options.defaultPolicy() == null ? prepared.defaultPolicy() : options.defaultPolicy();
        var modes = modes(options == null || options.modes() == null ? prepared.modes() : options.modes());
        var now = clock.instant();
        if (prepared.toolId() != null) {
            var version = versions.latestVersion(prepared.toolId()) + 1;
            var saved =
                    versions.save(new CustomToolVersion(prepared.toolId(), version, proposal, sessionId, itemId, now));
            catalog.applyVersion(prepared.toolId(), saved.spec(), version);
            catalog.applySettings(prepared.toolId(), policy, modes, true);
            return new ApprovedTool(prepared.toolId(), proposal.name(), version, prepared.scope());
        }
        var requestedScope = options == null || options.scope() == null ? prepared.scope() : options.scope();
        var scope = USER.equals(requestedScope) && prepared.userScopeAvailable() ? USER : PROJECT;
        var tool = catalog.create(
                userId, PROJECT.equals(scope) ? projectId : null, proposal.name(), proposal.spec(), policy, modes);
        versions.save(new CustomToolVersion(tool.getId(), 1, proposal, sessionId, itemId, now));
        return new ApprovedTool(tool.getId(), proposal.name(), 1, scope);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> executionArguments(ToolDefinition tool, Map<String, Object> arguments) {
        var version = currentVersion(tool);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("interpreter", version.getInterpreter());
        payload.put("body", version.getBody());
        payload.put("arguments", arguments);
        return payload;
    }

    @Transactional(readOnly = true)
    public List<CustomToolDtos.CustomToolResponse> list(UUID userId) {
        return catalog.owned(userId).stream().map(CustomToolService::response).toList();
    }

    @Transactional(readOnly = true)
    public List<CustomToolDtos.VersionResponse> versions(UUID userId, UUID toolId) {
        var tool = owned(userId, toolId);
        return versions.findByToolIdOrderByVersionDesc(toolId).stream()
                .map(version -> new CustomToolDtos.VersionResponse(
                        version.getVersion(),
                        version.getDescription(),
                        version.getParametersSchema(),
                        version.getInterpreter(),
                        version.getBody(),
                        version.getSessionId(),
                        version.getCreatedAt(),
                        version.getVersion() == tool.getVersion()))
                .toList();
    }

    @Transactional
    public CustomToolDtos.CustomToolResponse update(
            UUID userId, UUID toolId, CustomToolDtos.UpdateCustomToolRequest request) {
        var tool = owned(userId, toolId);
        var updated = catalog.applySettings(
                toolId,
                Optional.ofNullable(request.defaultPolicy()).orElse(tool.getDefaultPolicy()),
                modes(Optional.ofNullable(request.modes()).orElse(tool.getModes())),
                Optional.ofNullable(request.enabled()).orElse(tool.isEnabled()));
        return response(updated);
    }

    @Transactional
    public CustomToolDtos.CustomToolResponse restore(UUID userId, UUID toolId, int version) {
        owned(userId, toolId);
        var target = versions.findByToolIdAndVersion(toolId, version)
                .orElseThrow(() -> ApiException.notFound("version_not_found", "Version does not exist"));
        return response(catalog.applyVersion(toolId, target.spec(), version));
    }

    @Transactional
    public void delete(UUID userId, UUID toolId) {
        owned(userId, toolId);
        catalog.delete(toolId);
    }

    private ToolDefinition owned(UUID userId, UUID toolId) {
        return catalog.find(userId, toolId)
                .orElseThrow(() -> ApiException.notFound("tool_not_found", "Custom tool does not exist"));
    }

    private CustomToolVersion currentVersion(ToolDefinition tool) {
        return versions.findByToolIdAndVersion(tool.getId(), tool.getVersion())
                .orElseThrow(() ->
                        new IllegalStateException("Missing version " + tool.getVersion() + " of " + tool.getName()));
    }

    private ToolProposal parse(Map<String, Object> arguments) {
        var name = text(arguments, "name");
        if (!name.matches(NAME_PATTERN)) {
            throw new InvalidProposalException(
                    "name must be snake_case: a lowercase letter, then 2 to 47 lowercase letters, digits or _.");
        }
        var description = text(arguments, "description");
        if (description.isEmpty() || description.length() > MAX_DESCRIPTION_CHARS) {
            throw new InvalidProposalException(
                    "description is required and must be at most " + MAX_DESCRIPTION_CHARS + " characters.");
        }
        var interpreter = text(arguments, "interpreter");
        if (!ProposeTool.INTERPRETERS.contains(interpreter)) {
            throw new InvalidProposalException(
                    "interpreter must be one of " + String.join(", ", ProposeTool.INTERPRETERS) + ".");
        }
        var body = arguments.get("body") instanceof String text ? text : "";
        if (body.isBlank() || body.length() > MAX_BODY_CHARS) {
            throw new InvalidProposalException(
                    "body is required and must be at most " + MAX_BODY_CHARS + " characters.");
        }
        var scope = text(arguments, "scope");
        return new ToolProposal(
                name,
                description,
                schema(arguments.get("parameters")),
                interpreter,
                body,
                USER.equals(scope) ? USER : PROJECT);
    }

    private Map<String, Object> schema(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new InvalidProposalException("parameters must be a JSON Schema object.");
        }
        var schema = new LinkedHashMap<String, Object>();
        map.forEach((key, value) -> schema.put(String.valueOf(key), value));
        schema.putIfAbsent("type", "object");
        if (!"object".equals(schema.get("type"))) {
            throw new InvalidProposalException("parameters must have type object.");
        }
        if (schema.containsKey("properties") && !(schema.get("properties") instanceof Map<?, ?>)) {
            throw new InvalidProposalException("parameters.properties must be an object.");
        }
        if (json.writeValueAsString(schema).length() > MAX_SCHEMA_CHARS) {
            throw new InvalidProposalException("parameters is too large.");
        }
        return schema;
    }

    private static String text(Map<String, Object> arguments, String key) {
        return arguments.get(key) instanceof String text ? text.strip() : "";
    }

    private static List<String> modes(List<String> requested) {
        var modes = new ArrayList<String>();
        for (var mode : MODE_ORDER) {
            if (AGENT.equals(mode) || requested.contains(mode)) {
                modes.add(mode);
            }
        }
        return modes;
    }

    private static Map<String, Object> previous(CustomToolVersion version) {
        var previous = new LinkedHashMap<String, Object>();
        previous.put("version", version.getVersion());
        previous.put("description", version.getDescription());
        previous.put("parameters", version.getParametersSchema());
        previous.put("interpreter", version.getInterpreter());
        previous.put("body", version.getBody());
        return previous;
    }

    private static String scopeOf(ToolDefinition tool) {
        return tool.getProjectId() == null ? USER : PROJECT;
    }

    private static CustomToolDtos.CustomToolResponse response(ToolDefinition tool) {
        return new CustomToolDtos.CustomToolResponse(
                tool.getId(),
                tool.getName(),
                tool.getDescription(),
                scopeOf(tool),
                tool.getProjectId(),
                tool.getInterpreter(),
                tool.getParametersSchema(),
                tool.getVersion(),
                tool.getDefaultPolicy(),
                tool.getModes(),
                tool.isEnabled(),
                tool.getCreatedAt(),
                tool.getUpdatedAt());
    }

    public record PreparedProposal(
            ToolProposal proposal,
            UUID toolId,
            String scope,
            int version,
            boolean userScopeAvailable,
            String defaultPolicy,
            List<String> modes,
            Map<String, Object> previous) {

        public String name() {
            return proposal.name();
        }

        public Map<String, Object> preview() {
            var preview = new LinkedHashMap<String, Object>();
            preview.put("kind", "tool_proposal");
            preview.put("name", proposal.name());
            preview.put("description", proposal.description());
            preview.put("parameters", proposal.parametersSchema());
            preview.put("interpreter", proposal.interpreter());
            preview.put("body", proposal.body());
            preview.put("scope", scope);
            preview.put("scopeFixed", toolId != null);
            preview.put("userScopeAvailable", userScopeAvailable);
            preview.put("version", version);
            preview.put("defaultPolicy", defaultPolicy);
            preview.put("modes", modes);
            if (previous != null) {
                preview.put("previous", previous);
            }
            return preview;
        }
    }

    public record ApprovedTool(UUID toolId, String name, int version, String scope) {}

    public static final class InvalidProposalException extends RuntimeException {

        InvalidProposalException(String message) {
            super(message);
        }
    }
}
