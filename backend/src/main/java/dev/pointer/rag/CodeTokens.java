package dev.pointer.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class CodeTokens {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_]+");
    private static final Pattern PART_BOUNDARY = Pattern.compile(
            "_+|(?<=\\p{Ll})(?=\\p{Lu})|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})|(?<=\\p{L})(?=\\p{N})|(?<=\\p{N})(?=\\p{L})");
    private static final int MAX_TOKEN_LENGTH = 64;
    private static final int MAX_QUERY_TERMS = 16;
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "can", "do", "does", "for", "from", "how", "i", "in", "is",
            "it", "of", "on", "or", "should", "that", "the", "this", "to", "we", "what", "when", "where", "which",
            "who", "why", "with", "find", "show", "code", "и", "в", "во", "на", "с", "со", "к", "по", "о", "об", "от",
            "до", "из", "за", "для", "как", "где", "что", "это", "ли", "не", "а", "но", "или", "какой", "какая",
            "какие", "каким", "найди", "покажи", "код");

    private CodeTokens() {}

    static String expand(String text) {
        return String.join(" ", tokens(text));
    }

    static List<String> queryTerms(String query) {
        var terms = new LinkedHashSet<String>();
        for (var token : tokens(query)) {
            if (token.length() > 1 && !STOP_WORDS.contains(token) && terms.size() < MAX_QUERY_TERMS) {
                terms.add(token);
            }
        }
        return List.copyOf(terms);
    }

    static String tsQuery(List<String> terms) {
        return String.join(" | ", terms);
    }

    private static List<String> tokens(String text) {
        var tokens = new ArrayList<String>();
        if (text == null) {
            return tokens;
        }
        var matcher = WORD.matcher(text);
        while (matcher.find()) {
            var word = matcher.group();
            var parts = PART_BOUNDARY
                    .splitAsStream(word)
                    .filter(part -> !part.isEmpty())
                    .toList();
            add(tokens, String.join("", parts));
            if (parts.size() > 1) {
                parts.forEach(part -> add(tokens, part));
            }
        }
        return tokens;
    }

    private static void add(List<String> tokens, String token) {
        if (!token.isEmpty() && token.length() <= MAX_TOKEN_LENGTH) {
            tokens.add(token.toLowerCase(Locale.ROOT));
        }
    }
}
