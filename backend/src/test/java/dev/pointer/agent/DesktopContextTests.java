package dev.pointer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.pointer.provider.llm.ChatMessage;
import dev.pointer.session.AttachmentService;
import dev.pointer.session.Item;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DesktopContextTests {

    private final AttachmentService attachments = mock(AttachmentService.class);
    private final ContextAssembler assembler =
            new ContextAssembler(Clock.fixed(Instant.parse("2026-11-25T10:00:00Z"), ZoneOffset.UTC), attachments);
    private final List<Map<String, Object>> items = new ArrayList<>();
    private final UUID turnId = UUID.randomUUID();

    DesktopContextTests() {
        when(attachments.dataUrl(any()))
                .thenAnswer(
                        invocation -> Optional.of("data:image/png;base64," + invocation.getArgument(0, UUID.class)));
    }

    @Test
    void screenshotsOfAStepFollowItsToolResultsInOneUserMessageInCallOrder() {
        user("Check the app");
        var first = call(0, "call_1", "desktop_click", "{\"x\":640,\"y\":360}", true);
        var second = call(0, "call_2", "desktop_screenshot", "{}", true);
        call(1, "call_3", "read_file", "{\"path\":\"a\"}", false);

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
        assertThat(screenshots.imageUrls())
                .containsExactly("data:image/png;base64," + first, "data:image/png;base64," + second);
        assertThat(messages.get(3).content()).endsWith("Screenshot attached.");
    }

    @Test
    void theSameItemsRenderIdenticallyAndALaterStepOnlyAppends() {
        user("Check the app");
        call(0, "call_1", "desktop_click", "{\"x\":1,\"y\":2}", true);
        var before = build(true);
        assertThat(build(true)).isEqualTo(before);

        call(1, "call_2", "desktop_key", "{\"keys\":\"Return\"}", true);
        var after = build(true);

        assertThat(after.subList(0, before.size())).isEqualTo(before);
        assertThat(ContextAssembler.hasScreenshots(items)).isTrue();
    }

    @Test
    void aModelWithoutVisionGetsPlaceholdersInsteadOfImages() {
        user("Check the app");
        call(0, "call_1", "desktop_click", "{\"x\":640,\"y\":360}", true);

        var screenshots = build(false).getLast();

        assertThat(screenshots.role()).isEqualTo(ChatMessage.Role.USER);
        assertThat(screenshots.imageUrls()).isEmpty();
        assertThat(screenshots.content())
                .isEqualTo(ContextAssembler.SCREENSHOTS_UNSUPPORTED_HEADER
                        + "\n[Screenshot omitted: desktop_click({\"x\":640,\"y\":360})]");
    }

    private List<ChatMessage> build(boolean vision) {
        return assembler.build("agent", null, null, items, new LinkedHashMap<>(), MaskPolicy.NONE, vision);
    }

    private void user(String text) {
        items.add(item(Item.USER_MESSAGE, null, Map.of("text", text)));
    }

    private UUID call(int step, String callId, String toolName, String arguments, boolean screenshot) {
        var attachmentId = UUID.randomUUID();
        var fields = new HashMap<String, Object>(Map.of(
                "providerCallId",
                callId,
                "toolName",
                toolName,
                "argumentsJson",
                arguments,
                "resultText",
                screenshot ? "{}\nScreenshot attached." : "content"));
        if (screenshot) {
            fields.put(
                    "images",
                    List.of(Map.of(
                            "attachmentId",
                            attachmentId.toString(),
                            "mediaType",
                            "image/png",
                            "width",
                            1280,
                            "height",
                            800,
                            "zoom",
                            false)));
        }
        items.add(item(Item.TOOL_CALL, step, fields));
        return attachmentId;
    }

    private Map<String, Object> item(String type, Integer step, Map<String, Object> fields) {
        var item = new HashMap<>(fields);
        item.put("id", UUID.randomUUID());
        item.put("turnId", turnId);
        item.put("type", type);
        if (step != null) {
            item.put("step", step);
        }
        return item;
    }
}
