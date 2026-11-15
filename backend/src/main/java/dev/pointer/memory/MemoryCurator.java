package dev.pointer.memory;

import dev.pointer.common.error.ApiException;
import dev.pointer.provider.ProviderService;
import dev.pointer.provider.llm.Cancellation;
import dev.pointer.provider.llm.ChatMessage;
import dev.pointer.provider.llm.ChatRequest;
import dev.pointer.provider.llm.StreamListener;
import dev.pointer.routing.ModelRouter;
import dev.pointer.routing.RoutedClient;
import dev.pointer.session.SessionService;
import dev.pointer.session.UsageLedger;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class MemoryCurator {

    static final int MAX_SUGGESTIONS = 5;
    static final int MAX_TRANSCRIPT_CHARS = 24_000;
    private static final int MAX_ITEM_CHARS = 1_200;
    private static final int SESSIONS_PER_RUN = 5;
    private static final Logger log = LoggerFactory.getLogger(MemoryCurator.class);
    private static final String SYSTEM = """
            You maintain the long-term memory of Pointer, an AI software engineering agent, and summaries of its \
            past sessions. You read one finished session and reply with JSON only, no Markdown fence:
            {"summary": "...", "memories": [{"scope": "user|project", "kind": "user|feedback|project|reference", \
            "name": "kebab-case-name", "description": "one line", "content": "the fact"}]}

            summary: at most 150 words in English: the user's goal, what was done, the files involved, decisions \
            and the outcome. Write it so a later session can find this work by searching.

            memories: at most 5 facts worth remembering in later sessions, usually none or one or two. Only:
            - preferences the user stated or corrections of the agent's approach (kind feedback; content with \
            "Why:" and "How to apply:" lines);
            - facts about the user (kind user);
            - non-obvious project decisions, constraints or ongoing work not recorded in the code (kind project);
            - where to find external information (kind reference).
            Never suggest what the repository or project rules already say, task details that only mattered in \
            this session, or anything listed as existing or rejected below. Use scope user for preferences that \
            apply to every project and scope project otherwise. Write memories in the language of the user.""";

    private final DigestStore digests;
    private final MemoryService memories;
    private final MemoryStore memoryStore;
    private final MemoryEmbeddings embeddings;
    private final SessionService sessions;
    private final ProviderService providers;
    private final RoutedClient client;
    private final JsonMapper json;
    private final UsageLedger ledger;
    private final ModelRouter router;
    private final Clock clock;
    private final boolean enabled;
    private final Duration idle;
    private final Duration maxAge;
    private final int minUserMessages;
    private final AtomicBoolean running = new AtomicBoolean();

    MemoryCurator(
            DigestStore digests,
            MemoryService memories,
            MemoryStore memoryStore,
            MemoryEmbeddings embeddings,
            SessionService sessions,
            ProviderService providers,
            RoutedClient client,
            JsonMapper json,
            UsageLedger ledger,
            ModelRouter router,
            Clock clock,
            @Value("${pointer.memory.curation-enabled:true}") boolean enabled,
            @Value("${pointer.memory.curation-idle:15m}") Duration idle,
            @Value("${pointer.memory.curation-max-age:3d}") Duration maxAge,
            @Value("${pointer.memory.curation-min-messages:3}") int minUserMessages) {
        this.digests = digests;
        this.memories = memories;
        this.memoryStore = memoryStore;
        this.embeddings = embeddings;
        this.sessions = sessions;
        this.providers = providers;
        this.client = client;
        this.json = json;
        this.ledger = ledger;
        this.router = router;
        this.clock = clock;
        this.enabled = enabled;
        this.idle = idle;
        this.maxAge = maxAge;
        this.minUserMessages = minUserMessages;
    }

    @Scheduled(fixedDelayString = "${pointer.memory.curation-interval:60s}")
    void scheduled() {
        if (enabled) {
            curateIdleSessions();
        }
    }

    public int curateIdleSessions() {
        if (!running.compareAndSet(false, true)) {
            return 0;
        }
        try {
            var now = clock.instant();
            var candidates = digests.candidates(now.minus(idle), now.minus(maxAge), minUserMessages, SESSIONS_PER_RUN);
            candidates.forEach(this::curate);
            return candidates.size();
        } finally {
            running.set(false);
        }
    }

    private void curate(DigestStore.Candidate session) {
        try {
            var transcript = transcript(sessions.items(session.userId(), session.sessionId()));
            var reply = complete(session, transcript);
            var parsed = parse(reply);
            var suggested = 0;
            for (var draft : parsed.memories()) {
                if (suggested >= MAX_SUGGESTIONS) {
                    break;
                }
                var projectId = Memory.SCOPE_USER.equals(draft.scope()) ? null : session.projectId();
                try {
                    var memory = memories.suggest(session.userId(), projectId, draft.draft(), session.sessionId());
                    if (memory != null) {
                        suggested++;
                    }
                } catch (ApiException exception) {
                    log.debug(
                            "Skipped an invalid suggested memory {}",
                            draft.draft().name(),
                            exception);
                }
            }
            digests.saveDigest(session, parsed.summary(), suggested);
            embeddings
                    .document(session.userId(), session.deviceId(), parsed.summary())
                    .ifPresent(embedding -> digests.saveEmbedding(
                            session.sessionId(), embedding.modelId(), MemoryStore.literal(embedding.vector())));
        } catch (RuntimeException exception) {
            log.warn("Curating session {} failed: {}", session.sessionId(), exception.getMessage());
            digests.recordFailure(session);
        }
    }

    private String complete(DigestStore.Candidate session, String transcript) {
        var routed = router.auxiliary(
                session.userId(),
                session.smartRouting(),
                providers.resolve(session.userId(), session.modelId(), session.deviceId()),
                session.deviceId());
        var reply = new StringBuilder();
        var reported = new AtomicReference<StreamListener.Usage>();
        var used = client.failover(session.userId(), session.deviceId(), session.smartRouting()).stream(
                routed,
                ChatRequest.of(
                        routed.model().modelKey(),
                        List.of(
                                ChatMessage.system(SYSTEM),
                                ChatMessage.user(known(session) + "\n\nSession transcript:\n" + transcript))),
                new StreamListener() {
                    @Override
                    public void onText(String delta) {
                        reply.append(delta);
                    }

                    @Override
                    public void onUsage(Usage usage) {
                        reported.set(usage);
                    }
                },
                new Cancellation());
        ledger.record(session.sessionId(), null, used.model(), used.role(), UsageLedger.MEMORY, reported.get());
        return reply.toString();
    }

    private String known(DigestStore.Candidate session) {
        var lines = new ArrayList<String>();
        for (var projectId : Arrays.asList(null, session.projectId())) {
            for (var status : List.of(Memory.ACTIVE, Memory.SUGGESTED, Memory.DISMISSED)) {
                memoryStore
                        .listScope(session.userId(), projectId, status)
                        .forEach(memory -> lines.add("- " + memory.name() + " ("
                                + (Memory.DISMISSED.equals(status) ? "rejected" : "existing") + "): "
                                + memory.description()));
            }
        }
        return lines.isEmpty()
                ? "Existing and rejected memories: none."
                : "Existing and rejected memories:\n" + String.join("\n", lines);
    }

    static String transcript(List<Map<String, Object>> items) {
        var parts = new ArrayList<String>();
        for (var item : items) {
            if (item.get("parentItemId") != null) {
                continue;
            }
            var line = switch (String.valueOf(item.get("type"))) {
                case "user_message" -> "User: " + text(item.get("text"));
                case "assistant_message" -> "Agent: " + text(item.get("text"));
                case "compaction_summary" -> "Summary of earlier conversation: " + text(item.get("text"));
                case "plan" ->
                    "Agent plan (" + item.getOrDefault("approval", "proposed") + "): " + text(item.get("text"));
                case "tool_call" -> toolLine(item);
                default -> null;
            };
            if (line != null && !line.isBlank()) {
                parts.add(line);
            }
        }
        var result = new StringBuilder();
        for (var index = parts.size() - 1; index >= 0; index--) {
            var part = parts.get(index);
            if (result.length() + part.length() + 1 > MAX_TRANSCRIPT_CHARS) {
                break;
            }
            result.insert(0, part + "\n");
        }
        return result.toString();
    }

    private static String toolLine(Map<String, Object> item) {
        var status = String.valueOf(item.getOrDefault("toolStatus", ""));
        var line = "Tool " + item.get("toolName") + " " + text(String.valueOf(item.getOrDefault("arguments", "")))
                + " -> " + status;
        if (item.get("error") instanceof Map<?, ?> error && error.get("message") != null) {
            line += ": " + text(String.valueOf(error.get("message")));
        }
        return line;
    }

    private static String text(Object value) {
        var text = value == null ? "" : String.valueOf(value).strip();
        return text.length() > MAX_ITEM_CHARS ? text.substring(0, MAX_ITEM_CHARS) + "…" : text;
    }

    Curation parse(String reply) {
        var start = reply.indexOf('{');
        var end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("The model did not return JSON");
        }
        var root = json.readTree(reply.substring(start, end + 1));
        var summary = root.path("summary").asString("").strip();
        if (summary.isEmpty()) {
            throw new IllegalStateException("The model returned no summary");
        }
        var drafts = new ArrayList<Suggestion>();
        for (JsonNode node : root.path("memories")) {
            drafts.add(new Suggestion(
                    node.path("scope").asString(Memory.SCOPE_PROJECT),
                    new MemoryService.Draft(
                            node.path("kind").asString(""),
                            node.path("name").asString(""),
                            node.path("description").asString(""),
                            node.path("content").asString(""))));
        }
        return new Curation(summary, drafts);
    }

    record Curation(String summary, List<Suggestion> memories) {}

    record Suggestion(String scope, MemoryService.Draft draft) {}
}
