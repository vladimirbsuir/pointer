package dev.pointer.telegram;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class TelegramFormat {

    static final int CHUNK_LIMIT = 3500;

    private static final Pattern FENCE = Pattern.compile("^\\s*```\\s*([\\w+#.-]*)\\s*$");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s+(.*)$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\((https?://[^)\\s\"]+)\\)");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private TelegramFormat() {}

    static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    static String plain(String html) {
        return TAG.matcher(html)
                .replaceAll("")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&amp;", "&");
    }

    static String shorten(String text, int limit) {
        if (text == null) {
            return "";
        }
        var singleLine = text.strip().replaceAll("\\s+", " ");
        return singleLine.length() <= limit ? singleLine : singleLine.substring(0, limit - 1) + "…";
    }

    static String shortenBlock(String text, int limit) {
        var content = text.strip();
        if (content.length() <= limit) {
            return content;
        }
        var cut = content.substring(0, limit);
        var fences = cut.split("```", -1).length - 1;
        return cut + (fences % 2 == 1 ? "\n```" : "") + "\n…";
    }

    static String code(String text, int limit) {
        var content = text == null ? "" : text;
        if (content.length() > limit) {
            content = content.substring(0, limit) + "\n…";
        }
        return "<pre>" + escape(content) + "</pre>";
    }

    static List<String> markdownMessages(String markdown) {
        return split(markdown).stream().map(TelegramFormat::markdownToHtml).toList();
    }

    static String markdownToHtml(String markdown) {
        var html = new StringBuilder();
        var inCode = false;
        for (var line : markdown.split("\n", -1)) {
            var fence = FENCE.matcher(line);
            if (fence.matches()) {
                if (inCode) {
                    html.append("</code></pre>\n");
                } else {
                    var language = fence.group(1);
                    html.append(language.isEmpty() ? "<pre><code>" : "<pre><code class=\"language-" + language + "\">");
                }
                inCode = !inCode;
                continue;
            }
            html.append(inCode ? escape(line) : block(line)).append('\n');
        }
        if (inCode) {
            html.append("</code></pre>");
        }
        return html.toString().strip();
    }

    static List<String> split(String markdown) {
        var chunks = new ArrayList<String>();
        var current = new StringBuilder();
        String openFence = null;
        for (var rawLine : markdown.split("\n", -1)) {
            for (var line : hardWrap(rawLine)) {
                if (!current.isEmpty() && current.length() + line.length() + 1 > CHUNK_LIMIT) {
                    if (openFence != null) {
                        current.append("```\n");
                    }
                    addChunk(chunks, current);
                    current = new StringBuilder();
                    if (openFence != null) {
                        current.append(openFence).append('\n');
                    }
                }
                current.append(line).append('\n');
                if (FENCE.matcher(line).matches()) {
                    openFence = openFence == null ? line.strip() : null;
                }
            }
        }
        addChunk(chunks, current);
        return chunks;
    }

    private static void addChunk(List<String> chunks, StringBuilder chunk) {
        var text = chunk.toString().strip();
        if (!text.isEmpty()) {
            chunks.add(text);
        }
    }

    private static List<String> hardWrap(String line) {
        if (line.length() <= CHUNK_LIMIT) {
            return List.of(line);
        }
        var pieces = new ArrayList<String>();
        for (var start = 0; start < line.length(); start += CHUNK_LIMIT) {
            pieces.add(line.substring(start, Math.min(line.length(), start + CHUNK_LIMIT)));
        }
        return pieces;
    }

    private static String block(String line) {
        var heading = HEADING.matcher(line);
        if (heading.matches()) {
            return "<b>" + inline(heading.group(1)) + "</b>";
        }
        var bullet = BULLET.matcher(line);
        if (bullet.matches()) {
            return bullet.group(1) + "• " + inline(bullet.group(2));
        }
        return inline(line);
    }

    private static String inline(String text) {
        var parts = text.split("`", -1);
        if (parts.length % 2 == 0) {
            return spans(text);
        }
        var html = new StringBuilder();
        for (var index = 0; index < parts.length; index++) {
            if (index % 2 == 1) {
                html.append("<code>").append(escape(parts[index])).append("</code>");
            } else {
                html.append(spans(parts[index]));
            }
        }
        return html.toString();
    }

    private static String spans(String text) {
        var escaped = escape(text);
        escaped = LINK.matcher(escaped).replaceAll("<a href=\"$2\">$1</a>");
        return BOLD.matcher(escaped).replaceAll("<b>$1</b>");
    }
}
