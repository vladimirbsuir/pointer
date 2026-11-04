package dev.pointer.command;

import dev.pointer.common.error.ApiException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class SlashCommandService {

    static final String NAME_PATTERN = "[a-z0-9][a-z0-9-]{0,39}";

    private final SlashCommandRepository commands;
    private final Clock clock;

    @Transactional(readOnly = true)
    List<CommandResponse> list(UUID userId) {
        return commands.findByUserIdOrderByName(userId).stream()
                .map(SlashCommandService::toResponse)
                .toList();
    }

    @Transactional
    CommandResponse create(UUID userId, CommandRequest request) {
        requireFreeName(userId, request.name(), null);
        return toResponse(commands.save(
                new SlashCommand(userId, request.name(), description(request), request.prompt(), clock.instant())));
    }

    @Transactional
    CommandResponse update(UUID userId, UUID commandId, CommandRequest request) {
        var command = owned(userId, commandId);
        requireFreeName(userId, request.name(), commandId);
        command.change(request.name(), description(request), request.prompt(), clock.instant());
        return toResponse(commands.save(command));
    }

    @Transactional
    void delete(UUID userId, UUID commandId) {
        commands.delete(owned(userId, commandId));
    }

    private SlashCommand owned(UUID userId, UUID commandId) {
        return commands.findByIdAndUserId(commandId, userId)
                .orElseThrow(() -> ApiException.notFound("command_not_found", "Command does not exist"));
    }

    private void requireFreeName(UUID userId, String name, UUID exceptId) {
        commands.findByUserIdAndName(userId, name)
                .filter(existing -> !existing.getId().equals(exceptId))
                .ifPresent(existing -> {
                    throw ApiException.conflict("command_exists", "A command with this name already exists");
                });
    }

    private static String description(CommandRequest request) {
        return request.description() == null ? "" : request.description().strip();
    }

    private static CommandResponse toResponse(SlashCommand command) {
        return new CommandResponse(
                command.getId(),
                command.getName(),
                command.getDescription(),
                command.getPrompt(),
                command.getUpdatedAt());
    }

    record CommandResponse(UUID id, String name, String description, String prompt, Instant updatedAt) {}

    record CommandRequest(
            @NotBlank @Pattern(regexp = NAME_PATTERN) String name,
            @Size(max = 200) String description,
            @NotBlank @Size(max = 20_000) String prompt) {}
}
