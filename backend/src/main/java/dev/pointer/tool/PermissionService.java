package dev.pointer.tool;

import dev.pointer.common.error.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PermissionService {

    public static final String ALLOW = "allow";
    public static final String ASK = "ask";
    public static final String DENY = "deny";
    static final String POLICY_PATTERN = "allow|ask|deny";
    static final String RUN_COMMAND = "run_command";

    private final ToolRepository tools;
    private final PermissionPolicyRepository policies;
    private final CommandRuleRepository commandRules;
    private final SessionGrantRepository grants;
    private final Clock clock;

    @Transactional(readOnly = true)
    public String evaluate(
            UUID userId,
            UUID projectId,
            UUID sessionId,
            ToolDefinition tool,
            Map<String, Object> arguments,
            boolean autoApprove) {
        var command = RUN_COMMAND.equals(tool.getOperation()) ? normalizeCommand(arguments.get("command")) : null;
        var commandPolicy = command == null ? Optional.<String>empty() : commandRulePolicy(userId, projectId, command);
        if (commandPolicy.filter(DENY::equals).isPresent()) {
            return DENY;
        }
        var toolPolicy = toolPolicy(userId, projectId, tool);
        if (DENY.equals(toolPolicy) && commandPolicy.isEmpty()) {
            return DENY;
        }
        if (hasGrant(sessionId, tool, command)) {
            return ALLOW;
        }
        var policy = commandPolicy.orElse(toolPolicy);
        return autoApprove && ASK.equals(policy) ? ALLOW : policy;
    }

    @Transactional
    public void grantForSession(UUID sessionId, ToolDefinition tool, Map<String, Object> arguments) {
        var prefix = RUN_COMMAND.equals(tool.getOperation())
                ? commandPrefix(normalizeCommand(arguments.get("command")))
                : null;
        grants.save(new SessionGrant(sessionId, tool.getId(), prefix, clock.instant()));
    }

    @Transactional(readOnly = true)
    public PermissionsResponse get(UUID userId, UUID projectId) {
        var levelPolicies = policies.findExact(userId, projectId).stream()
                .collect(Collectors.toMap(PermissionPolicy::getToolId, PermissionPolicy::getPolicy));
        var toolEntries = tools.findBuiltin().stream()
                .map(tool -> new ToolPermission(
                        tool.getName(),
                        tool.getKind(),
                        tool.getDescription(),
                        tool.getDefaultPolicy(),
                        levelPolicies.get(tool.getId())))
                .toList();
        var rules = commandRules.findExact(userId, projectId).stream()
                .map(rule -> new CommandRuleDto(rule.getPrefix(), rule.getPolicy()))
                .toList();
        return new PermissionsResponse(toolEntries, rules);
    }

    @Transactional
    public PermissionsResponse update(UUID userId, UUID projectId, UpdatePermissionsRequest request) {
        var toolsByName =
                tools.findBuiltin().stream().collect(Collectors.toMap(ToolDefinition::getName, Function.identity()));
        var now = clock.instant();
        if (request.policies() != null) {
            policies.deleteExact(userId, projectId);
            policies.flush();
            request.policies().forEach((name, policy) -> {
                if (policy == null) {
                    return;
                }
                var tool = toolsByName.get(name);
                if (tool == null) {
                    throw ApiException.badRequest("unknown_tool", "Unknown tool: " + name);
                }
                if (!policy.matches(POLICY_PATTERN)) {
                    throw ApiException.badRequest("invalid_policy", "Invalid policy: " + policy);
                }
                policies.save(new PermissionPolicy(userId, projectId, tool.getId(), policy, now));
            });
        }
        if (request.commandRules() != null) {
            commandRules.deleteExact(userId, projectId);
            commandRules.flush();
            request.commandRules()
                    .forEach(rule -> commandRules.save(
                            new CommandRule(userId, projectId, normalizeCommand(rule.prefix()), rule.policy(), now)));
        }
        return get(userId, projectId);
    }

    private Optional<String> commandRulePolicy(UUID userId, UUID projectId, String command) {
        var applicable = commandRules.findApplicable(userId, projectId).stream()
                .filter(rule -> matchesPrefix(command, rule.getPrefix()))
                .toList();
        return applicable.stream()
                .filter(rule -> rule.getProjectId() != null)
                .max(Comparator.comparingInt(rule -> rule.getPrefix().length()))
                .or(() -> applicable.stream()
                        .filter(rule -> rule.getProjectId() == null)
                        .max(Comparator.comparingInt(rule -> rule.getPrefix().length())))
                .map(CommandRule::getPolicy);
    }

    private String toolPolicy(UUID userId, UUID projectId, ToolDefinition tool) {
        var applicable = policies.findApplicable(userId, projectId).stream()
                .filter(policy -> policy.getToolId().equals(tool.getId()))
                .toList();
        return applicable.stream()
                .filter(policy -> policy.getProjectId() != null)
                .findFirst()
                .or(() -> applicable.stream()
                        .filter(policy -> policy.getProjectId() == null)
                        .findFirst())
                .map(PermissionPolicy::getPolicy)
                .orElse(tool.getDefaultPolicy());
    }

    private boolean hasGrant(UUID sessionId, ToolDefinition tool, String command) {
        return grants.findBySessionId(sessionId).stream()
                .filter(grant -> grant.getToolId().equals(tool.getId()))
                .anyMatch(grant -> command == null
                        || grant.getCommandPrefix() == null
                        || matchesPrefix(command, grant.getCommandPrefix()));
    }

    static boolean matchesPrefix(String command, String prefix) {
        return command.equals(prefix) || command.startsWith(prefix + " ");
    }

    static String normalizeCommand(Object command) {
        return command == null ? "" : String.valueOf(command).strip().replaceAll("\\s+", " ");
    }

    static String commandPrefix(String command) {
        var tokens = command.split(" ");
        if (tokens.length > 1 && tokens[1].matches("[A-Za-z][\\w:-]*")) {
            return tokens[0] + " " + tokens[1];
        }
        return tokens[0];
    }

    public record ToolPermission(String name, String kind, String description, String defaultPolicy, String policy) {}

    public record CommandRuleDto(
            @NotBlank @Size(max = 500) String prefix,
            @NotBlank @Pattern(regexp = POLICY_PATTERN) String policy) {}

    public record PermissionsResponse(List<ToolPermission> tools, List<CommandRuleDto> commandRules) {}

    public record UpdatePermissionsRequest(
            Map<String, String> policies, @Size(max = 200) List<@Valid CommandRuleDto> commandRules) {}
}
