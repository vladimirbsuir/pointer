package dev.pointer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.pointer.provider.llm.ChatMessage;
import dev.pointer.provider.llm.TokenEstimate;
import dev.pointer.session.AttachmentService;
import dev.pointer.session.Item;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DesktopContextTests {

    private static final MaskPolicy RESULTS = new MaskPolicy(10, 10);

    private final AttachmentService attachments = mock(AttachmentService.class);
    private final List<Map<String, Object>> items = new ArrayList<>();
    private final UUID turnId = UUID.randomUUID();
    private ContextAssembler assembler = assembler(MaskPolicy.NONE);

    DesktopContextTests() {
        when(attachments.inlineImage(any()))
                .thenAnswer(invocation -> Optional.of(
                        new AttachmentService.InlineImage(url(invocation.getArgument(0, UUID.class)), 1280, 800)));
    }

    @Test
    void screenshotsOfAStepFollowItsToolResultsInOneUserMessageInCallOrder() {
        user("Check the app");
        var first = call(0, "call_1", "desktop_click", "{\"x\":640,\"y\":360}", 1);
        var second = call(0, "call_2", "desktop_screenshot", "{}", 1);
        call(1, "call_3", "read_file", "{\"path\":\"a\"}", 0);

        var messages = build(true);

        assertThat(messages)
                .extracting(ChatMessage::role)
                .containsExactly(
                        ChatMessage.Role.SYSTEM,
                        ChatMessage.Role.USER,
                        ChatMessage.Role.ASSISTANT,
                        ChatMessage.Role.TOOL,
                        ChatMessage.Role.TOOL,
                        ChatMessage.Role.USER,
                        ChatMessage.Role.ASSISTANT,
                        ChatMessage.Role.TOOL);
        var screenshots = messages.get(5);
        assertThat(screenshots.content())
                .isEqualTo(ContextAssembler.SCREENSHOTS_HEADER + "\n1. desktop_click({\"x\":640,\"y\":360})\n"
                        + "2. desktop_screenshot({})");
        assertThat(screenshots.imageUrls()).containsExactly(url(first.getFirst()), url(second.getFirst()));
        assertThat(messages.get(3).content()).endsWith("Screenshot attached.");
    }

    @Test
    void theSameItemsRenderIdenticallyAndALaterStepOnlyAppends() {
        user("Check the app");
        call(0, "call_1", "desktop_click", "{\"x\":1,\"y\":2}", 1);
        var before = build(true);
        assertThat(build(true)).isEqualTo(before);

        call(1, "call_2", "desktop_key", "{\"keys\":\"Return\"}", 1);
        var after = build(true);

        assertThat(after.subList(0, before.size())).isEqualTo(before);
        assertThat(assembler.hasVisibleScreenshots(items)).isTrue();
    }

    @Test
    void aModelWithoutVisionGetsPlaceholdersInsteadOfImages() {
        user("Check the app");
        call(0, "call_1", "desktop_click", "{\"x\":640,\"y\":360}", 1);

        var screenshots = build(false).getLast();

        assertThat(screenshots.role()).isEqualTo(ChatMessage.Role.USER);
        assertThat(screenshots.imageUrls()).isEmpty();
        assertThat(screenshots.content())
                .isEqualTo(ContextAssembler.SCREENSHOTS_UNSUPPORTED_HEADER
                        + "\n[Screenshot omitted: desktop_click({\"x\":640,\"y\":360})]");
    }

    @Test
    void theNewestScreenshotsStayAndOlderOnesAreMaskedInWholeBlocks() {
        assembler = assembler(new MaskPolicy(3, 4));
        user("Check the app");
        var expectedVisible = Map.of(1, 1, 3, 3, 6, 6, 7, 3, 10, 6, 11, 3);
        for (var step = 0; step < 11; step++) {
            call(step, "call_" + step, "desktop_click", "{\"x\":" + step + "}", 1);
            var visible = visibleImages(build(true));
            if (expectedVisible.containsKey(step + 1)) {
                assertThat(visible).as("after %d screenshots", step + 1).isEqualTo(expectedVisible.get(step + 1));
            }
        }

        var messages = build(true);
        var screenshots = screenshotMessages(messages);
        assertThat(screenshots).hasSize(11);
        assertThat(screenshots.get(7).content())
                .isEqualTo(ContextAssembler.SCREENSHOTS_HEADER + "\n[Screenshot omitted: desktop_click({\"x\":7})]");
        assertThat(screenshots.get(7).imageUrls()).isEmpty();
        assertThat(screenshots.get(8).content())
                .isEqualTo(ContextAssembler.SCREENSHOTS_HEADER + "\n1. desktop_click({\"x\":8})");
        assertThat(messages)
                .filteredOn(message -> message.role() == ChatMessage.Role.TOOL)
                .extracting(ChatMessage::content)
                .allMatch(content -> content.endsWith("Screenshot attached."));
    }

    @Test
    void aBlockOfOneMasksEveryScreenshotBeyondTheNewest() {
        assembler = assembler(new MaskPolicy(2, 1));
        user("Check the app");
        for (var step = 0; step < 6; step++) {
            call(step, "call_" + step, "desktop_click", "{\"x\":" + step + "}", 1);
            var screenshots = screenshotMessages(build(true));

            assertThat(screenshots)
                    .extracting(message -> message.imageUrls().size())
                    .as("after %d screenshots", step + 1)
                    .containsExactlyElementsOf(visibility(step + 1, Math.max(0, step - 1)));
        }
    }

    @Test
    void theBoundaryCanSplitTheScreenshotsOfOneStep() {
        assembler = assembler(new MaskPolicy(1, 1));
        user("Check the app");
        var first = call(0, "call_1", "desktop_click", "{\"x\":1}", 1);
        var second = call(0, "call_2", "desktop_drag", "{\"x\":2}", 2);

        var screenshots = screenshotMessages(build(true)).getFirst();

        assertThat(screenshots.content())
                .isEqualTo(ContextAssembler.SCREENSHOTS_HEADER
                        + "\n[Screenshot omitted: desktop_click({\"x\":1})]"
                        + "\n[Screenshot omitted: desktop_drag({\"x\":2})]"
                        + "\n1. desktop_drag({\"x\":2})");
        assertThat(screenshots.imageUrls()).containsExactly(url(second.get(1)));
        verify(attachments, never()).inlineImage(first.getFirst());
        verify(attachments, never()).inlineImage(second.getFirst());
    }

    @Test
    void imageMaskingIsTheSameUnderEveryContextStrategy() {
        assembler = assembler(new MaskPolicy(1, 1));
        user("Check the app");
        call(0, "call_1", "desktop_click", "{\"x\":1}", 1);
        call(1, "call_2", "desktop_click", "{\"x\":2}", 1);
        var policy = new ContextPolicy(10, 10, true, Duration.ofMinutes(5));

        for (var strategy : List.of("summary", "masking", "hybrid")) {
            var screenshots = screenshotMessages(
                    assembler.build("agent", null, null, items, new LinkedHashMap<>(), policy.mask(strategy), true));

            assertThat(screenshots)
                    .extracting(message -> message.imageUrls().size())
                    .as(strategy)
                    .containsExactly(0, 1);
        }
    }

    @Test
    void compactionInputsRenderScreenshotsAsSpecified() {
        assembler = assembler(new MaskPolicy(1, 1));
        user("Check the app");
        call(0, "call_1", "desktop_click", "{\"x\":1}", 1);
        call(1, "call_2", "desktop_click", "{\"x\":2}", 1);
        var nextTurn = UUID.randomUUID();
        items.add(item(Item.USER_MESSAGE, null, Map.of("text", "Next", "turnId", nextTurn)));

        var legacy = assembler.buildCompaction(ContextAssembler.compactable(items, nextTurn), RESULTS, false);
        var cacheSafe = assembler.buildCacheSafeCompaction(
                "agent", null, null, items, new LinkedHashMap<>(), RESULTS, true, nextTurn, false);
        var step = build(true);

        assertThat(screenshotMessages(legacy))
                .allMatch(message -> message.imageUrls().isEmpty()
                        && message.content().startsWith(ContextAssembler.SCREENSHOTS_UNSUPPORTED_HEADER));
        assertThat(step.subList(0, cacheSafe.size() - 1)).isEqualTo(cacheSafe.subList(0, cacheSafe.size() - 1));
        assertThat(screenshotMessages(cacheSafe))
                .extracting(message -> message.imageUrls().size())
                .containsExactly(0, 1);
    }

    @Test
    void screenshotsCountAsVisibleOnlyWhenTheMaskKeepsSome() {
        user("Check the app");
        call(0, "call_1", "desktop_click", "{\"x\":1}", 1);

        assertThat(assembler(new MaskPolicy(0, 1)).hasVisibleScreenshots(items)).isFalse();
        assertThat(assembler(new MaskPolicy(1, 1)).hasVisibleScreenshots(items)).isTrue();
        assertThat(assembler(new MaskPolicy(0, 4)).hasVisibleScreenshots(items)).isTrue();
        assertThat(assembler(new MaskPolicy(0, 1)).hasVisibleScreenshots(List.of()))
                .isFalse();
    }

    @Test
    void imagesOfKnownSizeAreEstimatedFromTheirPixels() {
        var known = UUID.randomUUID();
        var unknown = UUID.randomUUID();
        when(attachments.inlineImage(unknown))
                .thenReturn(Optional.of(new AttachmentService.InlineImage(url(unknown), null, null)));
        items.add(item(
                Item.USER_MESSAGE,
                null,
                Map.of(
                        "text",
                        "Look",
                        "attachments",
                        List.of(
                                Map.of("kind", "image", "attachmentId", known.toString()),
                                Map.of("kind", "image", "attachmentId", unknown.toString())))));
        call(0, "call_1", "desktop_click", "{\"x\":1}", 1);

        var messages = build(true);

        assertThat(messages.get(1).images())
                .extracting(ChatMessage.Image::estimatedTokens)
                .containsExactly(1_366, TokenEstimate.UNKNOWN_IMAGE_TOKENS);
        assertThat(messages.getLast().images())
                .extracting(ChatMessage.Image::estimatedTokens)
                .containsExactly(1_366);
        assertThat(TokenEstimate.image(1280, 800, 750)).isEqualTo(1_366);
        assertThat(TokenEstimate.image(1024, 768, 750)).isEqualTo(1_049);
        assertThat(TokenEstimate.image(null, 800, 750)).isEqualTo(TokenEstimate.UNKNOWN_IMAGE_TOKENS);
        var withoutImages = messages.stream()
                .map(message -> new ChatMessage(
                        message.role(), message.content(), message.toolCalls(), message.toolCallId(), List.of()))
                .toList();
        assertThat(TokenEstimate.messages(messages) - TokenEstimate.messages(withoutImages))
                .isEqualTo(1_366 + TokenEstimate.UNKNOWN_IMAGE_TOKENS + 1_366);
    }

    private static List<Integer> visibility(int total, int masked) {
        var sizes = new ArrayList<Integer>();
        for (var index = 0; index < total; index++) {
            sizes.add(index < masked ? 0 : 1);
        }
        return sizes;
    }

    private static int visibleImages(List<ChatMessage> messages) {
        return screenshotMessages(messages).stream()
                .mapToInt(message -> message.imageUrls().size())
                .sum();
    }

    private static List<ChatMessage> screenshotMessages(List<ChatMessage> messages) {
        return messages.stream()
                .filter(message -> message.role() == ChatMessage.Role.USER
                        && message.content().startsWith("Screenshots from this step"))
                .toList();
    }

    private static String url(UUID attachmentId) {
        return "data:image/png;base64," + attachmentId;
    }

    private ContextAssembler assembler(MaskPolicy images) {
        return TestAssemblers.assembler("2026-11-25T10:00:00Z", attachments, images);
    }

    private List<ChatMessage> build(boolean vision) {
        return assembler.build("agent", null, null, items, new LinkedHashMap<>(), MaskPolicy.NONE, vision);
    }

    private void user(String text) {
        items.add(item(Item.USER_MESSAGE, null, Map.of("text", text)));
    }

    private List<UUID> call(int step, String callId, String toolName, String arguments, int screenshots) {
        var attachmentIds = new ArrayList<UUID>();
        var images = new ArrayList<Map<String, Object>>();
        for (var index = 0; index < screenshots; index++) {
            var attachmentId = UUID.randomUUID();
            attachmentIds.add(attachmentId);
            images.add(Map.of(
                    "attachmentId",
                    attachmentId.toString(),
                    "mediaType",
                    "image/png",
                    "width",
                    1280,
                    "height",
                    800,
                    "zoom",
                    false));
        }
        var fields = new HashMap<String, Object>(Map.of(
                "providerCallId",
                callId,
                "toolName",
                toolName,
                "argumentsJson",
                arguments,
                "resultText",
                screenshots > 0 ? "{}\nScreenshot attached." : "content"));
        if (screenshots > 0) {
            fields.put("images", List.copyOf(images));
        }
        items.add(item(Item.TOOL_CALL, step, fields));
        return attachmentIds;
    }

    private Map<String, Object> item(String type, Integer step, Map<String, Object> fields) {
        var item = new HashMap<>(fields);
        item.put("id", UUID.randomUUID());
        item.putIfAbsent("turnId", turnId);
        item.put("type", type);
        if (step != null) {
            item.put("step", step);
        }
        return item;
    }
}
