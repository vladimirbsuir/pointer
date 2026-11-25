package dev.pointer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.pointer.provider.llm.ChatMessage;
import dev.pointer.session.AttachmentService;
import dev.pointer.session.Item;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.SequencedMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ContextAssemblerTests {

    private static final String CONTEXT_BLOCK = "<context>";
    private static final String PLACEHOLDER = "[Result of ";
    private static final ContextAssembler.ProjectContext PROJECT = new ContextAssembler.ProjectContext(
                    "demo", "main", "AGENTS.md", "Rules.", "Windows", "PowerShell")
            .withMemory("Memory:\nIndex:\n- note");

    private final AttachmentService attachments = mock(AttachmentService.class);
    private final ContextAssembler assembler = assembler(MaskPolicy.NONE);

    ContextAssemblerTests() {
        when(attachments.inlineImage(any()))
                .thenAnswer(invocation -> Optional.of(new AttachmentService.InlineImage(
                        "data:image/png;base64," + invocation.getArgument(0, UUID.class), 1280, 800)));
    }

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

    @ParameterizedTest
    @MethodSource("policies")
    void buildsAreAppendOnlyBetweenCompactionsAndMaskBoundaryMoves(Policies policies) {
        var assembler = assembler(policies.images());
        var random = new Random(20261118);
        var maskedResults = 0;
        var maskedImages = 0;
        for (var run = 0; run < 300; run++) {
            var session = GeneratedSession.generate(random);
            List<ChatMessage> previous = null;
            Boundaries previousBoundaries = null;
            for (var boundary : session.boundaries) {
                var messages = build(assembler, boundary.items(), boundary.snapshots(), policies.results());
                var active = ContextAssembler.activeItems(boundary.items());
                var boundaries = new Boundaries(
                        policies.results().boundary(ContextAssembler.countResults(active)),
                        policies.images().boundary(ContextAssembler.countScreenshots(active)));
                if (previous != null && !boundary.afterCompaction()) {
                    var first = firstDifference(previous, messages);
                    assertThat(messages.size()).isGreaterThan(previous.size());
                    if (boundaries.equals(previousBoundaries)) {
                        assertThat(first).as("run %d", run).isEqualTo(previous.size());
                    } else if (first < previous.size()) {
                        var changed = messages.get(first);
                        if (boundaries.results() == previousBoundaries.results()) {
                            assertThat(isMaskedScreenshots(changed))
                                    .as("run %d", run)
                                    .isTrue();
                        } else {
                            assertThat(isMaskedResult(changed) || isMaskedScreenshots(changed))
                                    .as("run %d", run)
                                    .isTrue();
                        }
                    }
                }
                if (boundary.afterCompaction() && !boundary.snapshots().isEmpty()) {
                    assertThat(messages.stream()
                                    .filter(message -> message.content().contains(CONTEXT_BLOCK)))
                            .isNotEmpty();
                }
                assertThat(build(assembler, boundary.items(), boundary.snapshots(), policies.results()))
                        .isEqualTo(messages);
                maskedResults += (int) messages.stream()
                        .filter(ContextAssemblerTests::isMaskedResult)
                        .count();
                maskedImages += (int) messages.stream()
                        .filter(ContextAssemblerTests::isMaskedScreenshots)
                        .count();
                previous = messages;
                previousBoundaries = boundaries;
            }
        }
        if (policies.results() == MaskPolicy.NONE) {
            assertThat(maskedResults).isZero();
        } else {
            assertThat(maskedResults).isPositive();
        }
        if (policies.images() == MaskPolicy.NONE) {
            assertThat(maskedImages).isZero();
        } else {
            assertThat(maskedImages).isPositive();
        }
    }

    @ParameterizedTest
    @MethodSource("policies")
    void theCacheSafeCompactionRequestExtendsAPrefixOfTheStepRequest(Policies policies) {
        var assembler = assembler(policies.images());
        var policy = policies.results();
        var random = new Random(20261119);
        var checked = 0;
        for (var run = 0; run < 300; run++) {
            for (var boundary : GeneratedSession.generate(random).boundaries) {
                var currentTurn = (UUID) boundary.items().getLast().get("turnId");
                if (ContextAssembler.compactable(boundary.items(), currentTurn).isEmpty()) {
                    continue;
                }
                var step = build(assembler, boundary.items(), boundary.snapshots(), policy);
                var continues = ContextAssembler.carriedSummary(
                                ContextAssembler.compactable(boundary.items(), currentTurn), 10_000)
                        != null;
                var compaction = assembler.buildCacheSafeCompaction(
                        "agent",
                        "Be brief.",
                        PROJECT,
                        boundary.items(),
                        boundary.snapshots(),
                        policy,
                        true,
                        currentTurn,
                        continues);
                var prefix = compaction.subList(0, compaction.size() - 1);

                assertThat(step.subList(0, prefix.size())).as("run %d", run).isEqualTo(prefix);
                assertThat(step.size()).isGreaterThan(prefix.size());
                assertThat(compaction.getLast().content())
                        .contains("do not call any tools")
                        .contains(
                                continues
                                        ? "Summarize only the conversation after"
                                        : "Summarize the conversation so far");
                checked++;
            }
        }
        assertThat(checked).isPositive();
    }

    @Test
    void anEarlierSummaryIsCarriedOnlyWhenItLeadsTheCoveredItemsAndFitsTheLimit() {
        var conversation = new Conversation();
        var first = conversation.turn("first question");
        conversation.answer(first, 0, "first answer");
        assertThat(ContextAssembler.carriedSummary(conversation.items, 1_000)).isNull();

        var second = conversation.turn("second question");
        conversation.summary(second, "The codename is HERON-7.");
        conversation.answer(second, 0, "second answer");
        var covered = ContextAssembler.compactable(conversation.items, UUID.randomUUID());

        assertThat(ContextAssembler.carriedSummary(covered, 1_000)).isEqualTo("The codename is HERON-7.");
        assertThat(ContextAssembler.carriedSummary(covered, 2)).isNull();
        assertThat(assembler
                        .buildCompaction(covered, MaskPolicy.NONE, true)
                        .getLast()
                        .content())
                .contains("kept word for word")
                .contains("Summarize only the conversation after that message");
    }

    @Test
    void theMaskBoundaryMovesInWholeBlocksAfterTheNewestResults() {
        var aligned = new MaskPolicy(10, 10);
        assertThat(aligned.boundary(0)).isZero();
        assertThat(aligned.boundary(10)).isZero();
        assertThat(aligned.boundary(19)).isZero();
        assertThat(aligned.boundary(20)).isEqualTo(10);
        assertThat(aligned.boundary(29)).isEqualTo(10);
        assertThat(aligned.boundary(30)).isEqualTo(20);
        var perStep = new MaskPolicy(10, 1);
        assertThat(perStep.boundary(11)).isEqualTo(1);
        assertThat(perStep.boundary(25)).isEqualTo(15);
        assertThat(MaskPolicy.NONE.boundary(1_000)).isZero();
    }

    @Test
    void maskingReplacesOldLongResultsAndKeepsCallsTextAndShortResults() {
        var conversation = new Conversation();
        var turn = conversation.turn("question");
        conversation.answer(turn, 0, "let me look");
        conversation.call(turn, 0, "call_a", "{\"path\":\"a.txt\"}", "line\n".repeat(60));
        conversation.call(turn, 0, "call_b", "{\"path\":\"b.txt\"}", "short");
        conversation.call(turn, 1, "call_c", "{\"path\":\"c.txt\"}", "line\n".repeat(60));
        conversation.call(turn, 1, "call_d", "{\"path\":\"d.txt\"}", "line\n".repeat(60));

        var messages = build(conversation.items, new LinkedHashMap<>(), new MaskPolicy(1, 1));

        assertThat(messages)
                .extracting(ChatMessage::content)
                .containsExactly(
                        messages.getFirst().content(),
                        "question",
                        "let me look",
                        "[Result of read_file({\"path\":\"a.txt\"}) omitted: 60 lines. Run the tool again if you need it.]",
                        "short",
                        "",
                        "[Result of read_file({\"path\":\"c.txt\"}) omitted: 60 lines. Run the tool again if you need it.]",
                        "line\n".repeat(60));
        assertThat(messages.get(2).toolCalls())
                .extracting(ChatMessage.ToolCall::id)
                .containsExactly("call_a", "call_b");
        assertThat(build(conversation.items, new LinkedHashMap<>(), MaskPolicy.NONE))
                .extracting(ChatMessage::content)
                .noneMatch(content -> content.startsWith(PLACEHOLDER));
        assertThat(assembler.buildCompaction(conversation.items, new MaskPolicy(1, 1), false))
                .extracting(ChatMessage::content)
                .filteredOn(content -> content.startsWith(PLACEHOLDER))
                .hasSize(2);
    }

    @Test
    void longArgumentsAreShortenedInThePlaceholder() {
        var call = Map.<String, Object>of(
                "toolName", "grep", "argumentsJson", "{\"pattern\":\"" + "x".repeat(200) + "\"}");

        assertThat(ContextAssembler.placeholder(call, "a\nb"))
                .startsWith("[Result of grep({\"pattern\":\"xxx")
                .contains("...) omitted: 2 lines.");
    }

    @Test
    void theStrategiesMapToMaskingAndCompactionThresholds() {
        var policy = new ContextPolicy(10, 10, true, Duration.ofMinutes(5));

        assertThat(policy.mask("summary")).isEqualTo(MaskPolicy.NONE);
        assertThat(policy.mask("masking")).isEqualTo(new MaskPolicy(10, 10));
        assertThat(policy.mask("hybrid")).isEqualTo(new MaskPolicy(10, 10));
        assertThat(policy.compactionRatio("summary")).isEqualTo(0.8);
        assertThat(policy.compactionRatio("hybrid")).isEqualTo(0.8);
        assertThat(policy.compactionRatio("masking")).isEqualTo(0.95);
        assertThat(policy.softCompaction("summary")).isTrue();
        assertThat(policy.softCompaction("hybrid")).isTrue();
        assertThat(policy.softCompaction("masking")).isFalse();
        assertThat(new ContextPolicy(10, 10, false, Duration.ofMinutes(5)).softCompaction("summary"))
                .isFalse();
    }

    static List<Policies> policies() {
        return List.of(
                new Policies(MaskPolicy.NONE, MaskPolicy.NONE),
                new Policies(MaskPolicy.NONE, new MaskPolicy(3, 4)),
                new Policies(new MaskPolicy(3, 3), new MaskPolicy(1, 1)),
                new Policies(new MaskPolicy(3, 1), new MaskPolicy(0, 2)),
                new Policies(new MaskPolicy(0, 2), MaskPolicy.NONE));
    }

    private static boolean isMaskedResult(ChatMessage message) {
        return message.role() == ChatMessage.Role.TOOL && message.content().startsWith(PLACEHOLDER);
    }

    private static boolean isMaskedScreenshots(ChatMessage message) {
        return message.role() == ChatMessage.Role.USER
                && message.content().startsWith(ContextAssembler.SCREENSHOTS_HEADER)
                && message.content().contains(ContextAssembler.SCREENSHOT_OMITTED);
    }

    private ContextAssembler assembler(MaskPolicy images) {
        return TestAssemblers.assembler("2026-11-18T10:00:00Z", attachments, images);
    }

    private static int firstDifference(List<ChatMessage> previous, List<ChatMessage> current) {
        var index = 0;
        while (index < previous.size()
                && index < current.size()
                && previous.get(index).equals(current.get(index))) {
            index++;
        }
        return index;
    }

    private List<ChatMessage> build(List<Map<String, Object>> items, SequencedMap<UUID, String> snapshots) {
        return build(items, snapshots, MaskPolicy.NONE);
    }

    private List<ChatMessage> build(
            List<Map<String, Object>> items, SequencedMap<UUID, String> snapshots, MaskPolicy policy) {
        return assembler.build("agent", "Be brief.", PROJECT, items, snapshots, policy, false);
    }

    private static List<ChatMessage> build(
            ContextAssembler assembler,
            List<Map<String, Object>> items,
            SequencedMap<UUID, String> snapshots,
            MaskPolicy policy) {
        return assembler.build("agent", "Be brief.", PROJECT, items, snapshots, policy, true);
    }

    record Policies(MaskPolicy results, MaskPolicy images) {}

    private record Boundaries(int results, int images) {}

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
                if (random.nextInt(3) == 0) {
                    desktopCall(turnId, step, callId, random.nextInt(4) == 0 ? 2 : 1);
                    continue;
                }
                var result =
                        random.nextInt(5) < 3 ? ("contents of " + callId + "\n").repeat(20) : "contents of " + callId;
                call(turnId, step, callId, "{\"path\":\"" + callId + "\"}", result);
            }
        }

        void desktopCall(UUID turnId, int step, String callId, int screenshots) {
            var images = new ArrayList<Map<String, Object>>();
            for (var index = 0; index < screenshots; index++) {
                images.add(Map.of(
                        "attachmentId",
                        UUID.randomUUID().toString(),
                        "mediaType",
                        "image/png",
                        "width",
                        1280,
                        "height",
                        800));
            }
            items.add(item(
                    turnId,
                    Item.TOOL_CALL,
                    step,
                    Map.of(
                            "providerCallId",
                            callId,
                            "toolName",
                            "desktop_click",
                            "argumentsJson",
                            "{\"x\":" + calls + ",\"y\":1}",
                            "resultText",
                            "{}\nScreenshot attached.",
                            "images",
                            List.copyOf(images))));
        }

        void call(UUID turnId, int step, String callId, String arguments, String result) {
            items.add(item(
                    turnId,
                    Item.TOOL_CALL,
                    step,
                    Map.of(
                            "providerCallId", callId,
                            "toolName", "read_file",
                            "argumentsJson", arguments,
                            "resultText", result)));
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
