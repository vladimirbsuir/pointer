package dev.pointer.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pointer.provider.llm.ChatMessage;
import dev.pointer.session.Item;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SequencedMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContextAssemblerTests {

    private static final String CONTEXT_BLOCK = "<context>";
    private static final ContextAssembler.ProjectContext PROJECT = new ContextAssembler.ProjectContext(
                    "demo", "main", "AGENTS.md", "Rules.", "Windows", "PowerShell")
            .withMemory("Memory:\nIndex:\n- note");

    private final ContextAssembler assembler =
            new ContextAssembler(Clock.fixed(Instant.parse("2026-11-18T10:00:00Z"), ZoneOffset.UTC), null);

    @Test
    void theSystemPromptHoldsOnlyWhatTheUserEdits() {
        var system = build(List.of(), new LinkedHashMap<>()).getFirst().content();

        assertThat(system)
                .contains("- OS: Windows", "- Shell: PowerShell", "- Project: demo", "Rules.")
                .doesNotContain("Date:", "Git branch", "Memory:");
        assertThat(assembler.snapshot(PROJECT))
                .isEqualTo("- Date: 2026-11-18\n- Git branch: main\n\nMemory:\nIndex:\n- note");
    }

    @Test
    void theSnapshotIsRenderedWithTheFirstUserMessageOfItsTurn() {
        var conversation = new Conversation();
        var first = conversation.turn("first");
        conversation.answer(first, 0, "answer");
        var second = conversation.turn("second");
        conversation.user(second, "queued");
        var snapshots = new LinkedHashMap<UUID, String>();
        snapshots.put(first, "- Date: one");

        var messages = build(conversation.items, snapshots);

        assertThat(messages)
                .extracting(ChatMessage::content)
                .containsExactly(
                        messages.getFirst().content(),
                        "<context>\nCurrent environment and memory. This block replaces any earlier context block.\n"
                                + "- Date: one\n</context>\n\nfirst",
                        "answer",
                        "second",
                        "queued");
    }

    @Test
    void aCompactionCarriesTheLatestSnapshotInFrontOfTheSummary() {
        var conversation = new Conversation();
        var first = conversation.turn("first");
        conversation.answer(first, 0, "answer");
        var second = conversation.turn("second");
        conversation.summary(second, "summary text");
        var snapshots = new LinkedHashMap<UUID, String>();
        snapshots.put(first, "- Date: one");

        var messages = build(conversation.items, snapshots);

        assertThat(messages.get(1).content())
                .startsWith(CONTEXT_BLOCK)
                .contains("- Date: one")
                .endsWith("Summary of the earlier conversation:\nsummary text");
        assertThat(messages.get(2).content()).isEqualTo("second");

        conversation.answer(second, 1, "second answer");
        var third = conversation.turn("third");
        snapshots.put(third, "- Date: three");
        var later = build(conversation.items, snapshots);
        assertThat(later.subList(0, messages.size())).isEqualTo(messages);
        assertThat(later.getLast().content()).contains("- Date: three").endsWith("third");

        snapshots.remove(third);
        snapshots.put(second, "- Date: two");
        var withOwnSnapshot = build(conversation.items, snapshots);
        assertThat(withOwnSnapshot.get(1).content()).isEqualTo("Summary of the earlier conversation:\nsummary text");
        assertThat(withOwnSnapshot.get(2).content()).contains("- Date: two").endsWith("second");
    }

    @Test
    void buildsAreAppendOnlyBetweenCompactions() {
        var random = new Random(20261118);
        for (var run = 0; run < 300; run++) {
            var session = GeneratedSession.generate(random);
            List<ChatMessage> previous = null;
            for (var boundary : session.boundaries) {
                var messages = build(boundary.items(), boundary.snapshots());
                if (previous != null && !boundary.afterCompaction()) {
                    assertThat(messages.size()).isGreaterThan(previous.size());
                    assertThat(messages.subList(0, previous.size()))
                            .as("run %d", run)
                            .isEqualTo(previous);
                }
                if (boundary.afterCompaction() && !boundary.snapshots().isEmpty()) {
                    assertThat(messages.stream()
                                    .filter(message -> message.content().contains(CONTEXT_BLOCK)))
                            .isNotEmpty();
                }
                assertThat(build(boundary.items(), boundary.snapshots())).isEqualTo(messages);
                previous = messages;
            }
        }
    }

    private List<ChatMessage> build(List<Map<String, Object>> items, SequencedMap<UUID, String> snapshots) {
        return assembler.build("agent", "Be brief.", PROJECT, items, snapshots, false);
    }

    private record Boundary(
            List<Map<String, Object>> items, SequencedMap<UUID, String> snapshots, boolean afterCompaction) {}

    private static final class GeneratedSession {

        final List<Boundary> boundaries = new ArrayList<>();
        private final Conversation conversation = new Conversation();
        private final LinkedHashMap<UUID, String> snapshots = new LinkedHashMap<>();
        private boolean compacted;

        static GeneratedSession generate(Random random) {
            var session = new GeneratedSession();
            var turns = 2 + random.nextInt(7);
            var compactAt = random.nextInt(3) == 0 ? 1 + random.nextInt(turns - 1) : -1;
            for (var turn = 0; turn < turns; turn++) {
                var turnId = session.conversation.turn("question " + turn);
                if (turn == 0 || random.nextBoolean()) {
                    session.snapshots.put(turnId, "- Date: day " + turn + "\n\nMemory:\n- note " + random.nextInt(3));
                }
                if (turn == compactAt) {
                    session.conversation.summary(turnId, "summary before turn " + turn);
                    session.compacted = true;
                }
                session.boundary();
                var steps = random.nextInt(4);
                for (var step = 0; step < steps; step++) {
                    session.conversation.toolStep(turnId, step, random);
                    if (random.nextInt(5) == 0) {
                        session.conversation.user(turnId, "follow-up " + turn + "." + step);
                    }
                    session.boundary();
                }
                session.conversation.answer(turnId, steps, "answer " + turn);
            }
            return session;
        }

        private void boundary() {
            boundaries.add(new Boundary(List.copyOf(conversation.items), new LinkedHashMap<>(snapshots), compacted));
            compacted = false;
        }
    }

    private static final class Conversation {

        final List<Map<String, Object>> items = new ArrayList<>();
        private int calls;

        UUID turn(String text) {
            var turnId = UUID.randomUUID();
            user(turnId, text);
            return turnId;
        }

        void user(UUID turnId, String text) {
            items.add(item(turnId, Item.USER_MESSAGE, null, Map.of("text", text)));
        }

        void answer(UUID turnId, int step, String text) {
            items.add(item(turnId, Item.ASSISTANT_MESSAGE, step, Map.of("text", text)));
        }

        void summary(UUID turnId, String text) {
            items.add(item(turnId, Item.COMPACTION_SUMMARY, 0, Map.of("text", text, "status", Item.COMPLETED)));
        }

        void toolStep(UUID turnId, int step, Random random) {
            if (random.nextBoolean()) {
                answer(turnId, step, "thinking " + step);
            }
            var count = 1 + random.nextInt(3);
            for (var index = 0; index < count; index++) {
                var callId = "call_" + calls++;
                items.add(item(
                        turnId,
                        Item.TOOL_CALL,
                        step,
                        Map.of(
                                "providerCallId",
                                callId,
                                "toolName",
                                "read_file",
                                "argumentsJson",
                                "{\"path\":\"" + callId + "\"}",
                                "resultText",
                                "contents of " + callId)));
            }
        }

        private static Map<String, Object> item(UUID turnId, String type, Integer step, Map<String, Object> fields) {
            var item = new HashMap<String, Object>(fields);
            item.put("id", UUID.randomUUID());
            item.put("turnId", turnId);
            item.put("type", type);
            if (step != null) {
                item.put("step", step);
            }
            return item;
        }
    }
}
