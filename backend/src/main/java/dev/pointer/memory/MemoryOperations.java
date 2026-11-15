package dev.pointer.memory;

import dev.pointer.common.error.ApiException;
import dev.pointer.execution.ExecutionRequest;
import dev.pointer.execution.ExecutionResult;
import dev.pointer.execution.ServerOperation;
import dev.pointer.provider.llm.Cancellation;
import dev.pointer.session.SessionService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class MemoryOperations {

    private static final int DEFAULT_SEARCH_LIMIT = 8;
    private static final int MAX_SEARCH_LIMIT = 20;
    private static final int MAX_READ_NAMES = 10;

    @Bean
    ServerOperation memorySaveOperation(MemoryService memories, SessionService sessions) {
        return new MemoryOperation("memory_save", sessions, (userId, request) -> {
            var arguments = request.arguments();
            var draft = new MemoryService.Draft(
                    text(arguments, "kind"),
                    text(arguments, "name"),
                    text(arguments, "description"),
                    text(arguments, "content"));
            return memories.save(
                            userId,
                            projectFor(arguments, request),
                            draft,
                            "agent",
                            request.sessionId(),
                            request.deviceId())
                    .toResult();
        });
    }

    @Bean
    ServerOperation memoryReadOperation(MemoryService memories, SessionService sessions) {
        return new MemoryOperation("memory_read", sessions, (userId, request) -> {
            var names = request.arguments().get("names") instanceof List<?> list
                    ? list.stream().map(String::valueOf).limit(MAX_READ_NAMES).toList()
                    : List.<String>of();
            if (names.isEmpty()) {
                throw ApiException.badRequest("invalid_arguments", "Missing argument names");
            }
            var found = memories.read(userId, request.projectId(), names);
            var foundNames = found.stream().map(Memory::name).toList();
            var result = new LinkedHashMap<String, Object>();
            result.put("memories", found.stream().map(MemoryOperations::full).toList());
            result.put(
                    "missing",
                    names.stream().filter(name -> !foundNames.contains(name)).toList());
            return result;
        });
    }

    @Bean
    ServerOperation memorySearchOperation(MemoryService memories, SessionService sessions) {
        return new MemoryOperation("memory_search", sessions, (userId, request) -> {
            var query = text(request.arguments(), "query");
            if (query == null || query.isBlank()) {
                throw ApiException.badRequest("invalid_arguments", "Missing argument query");
            }
            var limit = request.arguments().get("limit") instanceof Number number
                    ? Math.clamp(number.intValue(), 1, MAX_SEARCH_LIMIT)
                    : DEFAULT_SEARCH_LIMIT;
            var hits = memories.search(userId, request.projectId(), query, limit, request.deviceId());
            return Map.of(
                    "results",
                    hits.stream()
                            .map(hit -> {
                                var entry = summary(hit.memory());
                                entry.put("score", Math.round(hit.score() * 1000) / 1000.0);
                                return entry;
                            })
                            .toList());
        });
    }

    @Bean
    ServerOperation memoryDeleteOperation(MemoryService memories, SessionService sessions) {
        return new MemoryOperation(
                "memory_delete",
                sessions,
                (userId, request) -> memories.deleteByName(
                                userId, projectFor(request.arguments(), request), text(request.arguments(), "name"))
                        .toResult());
    }

    private static UUID projectFor(Map<String, Object> arguments, ExecutionRequest request) {
        return Memory.SCOPE_USER.equals(text(arguments, "scope")) ? null : request.projectId();
    }

    private static String text(Map<String, Object> arguments, String name) {
        return arguments.get(name) instanceof String value ? value : null;
    }

    private static Map<String, Object> summary(Memory memory) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("name", memory.name());
        entry.put("scope", memory.scope());
        entry.put("kind", memory.kind());
        entry.put("description", memory.description());
        return entry;
    }

    private static Map<String, Object> full(Memory memory) {
        var entry = summary(memory);
        entry.put("content", memory.content());
        entry.put("updatedAt", memory.updatedAt().toString());
        return entry;
    }

    interface Handler {
        Map<String, Object> handle(UUID userId, ExecutionRequest request);
    }

    private record MemoryOperation(String operation, SessionService sessions, Handler handler)
            implements ServerOperation {

        @Override
        public boolean isAvailable(UUID projectId) {
            return true;
        }

        @Override
        public ExecutionResult execute(ExecutionRequest request, Cancellation cancellation) {
            var userId = request.sessionId() == null
                    ? null
                    : sessions.ownerOf(request.sessionId()).orElse(null);
            if (userId == null) {
                return ExecutionResult.failure(ExecutionResult.FAILED, "session_not_found", "Session does not exist");
            }
            try {
                return new ExecutionResult(ExecutionResult.SUCCEEDED, handler.handle(userId, request), null, List.of());
            } catch (ApiException exception) {
                return ExecutionResult.failure(ExecutionResult.FAILED, exception.code(), exception.getMessage());
            }
        }
    }
}
