package dev.pointer.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TelegramFormatTest {

    @Test
    void convertsMarkdownToTelegramHtml() {
        var markdown = """
                # Result
                Use `a < b` and **bold** text, see [docs](https://example.com/a?b=1&c=2).
                - first item
                ```java
                if (a < b) {}
                ```""";

        assertThat(TelegramFormat.markdownToHtml(markdown)).isEqualTo("""
                        <b>Result</b>
                        Use <code>a &lt; b</code> and <b>bold</b> text, see <a href="https://example.com/a?b=1&amp;c=2">docs</a>.
                        • first item
                        <pre><code class="language-java">if (a &lt; b) {}
                        </code></pre>""");
    }

    @Test
    void escapesUnbalancedBackticksAsText() {
        assertThat(TelegramFormat.markdownToHtml("a ` b <c>")).isEqualTo("a ` b &lt;c&gt;");
    }

    @Test
    void splitsLongMessagesAndReopensCodeBlocks() {
        var line = "x".repeat(100);
        var markdown = "```\n" + (line + "\n").repeat(60) + "```";

        var chunks = TelegramFormat.split(markdown);

        assertThat(chunks).hasSize(2);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.length()).isLessThanOrEqualTo(TelegramFormat.CHUNK_LIMIT + 4);
            assertThat(chunk).startsWith("```").endsWith("```");
        });
    }

    @Test
    void stripsTagsForPlainFallback() {
        assertThat(TelegramFormat.plain("<b>a &lt; b</b> &amp; c")).isEqualTo("a < b & c");
    }

    @Test
    void encodesCallbackDataWithinTelegramLimit() {
        var id = UUID.randomUUID();

        var data = CallbackData.encode(CallbackData.APPROVAL, id, CallbackData.DENY);

        assertThat(data.length()).isLessThanOrEqualTo(64);
        assertThat(CallbackData.decode(data)).contains(new CallbackData(CallbackData.APPROVAL, id, CallbackData.DENY));
        assertThat(CallbackData.decode("garbage")).isEmpty();
    }
}
