package dev.pointer.provider;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record EmbeddingPrefixes(String query, String document) {

    public static final EmbeddingPrefixes NONE = new EmbeddingPrefixes(null, null);

    private static final String RETRIEVAL_INSTRUCTION = "Represent this sentence for searching relevant passages: ";

    private static final List<KnownFamily> KNOWN_FAMILIES = List.of(
            new KnownFamily(family("nomic-embed"), new EmbeddingPrefixes("search_query: ", "search_document: ")),
            new KnownFamily(family("e5"), new EmbeddingPrefixes("query: ", "passage: ")),
            new KnownFamily(family("bge-(small|base|large)"), new EmbeddingPrefixes(RETRIEVAL_INSTRUCTION, null)),
            new KnownFamily(family("mxbai-embed"), new EmbeddingPrefixes(RETRIEVAL_INSTRUCTION, null)),
            new KnownFamily(family("snowflake-arctic-embed"), new EmbeddingPrefixes(RETRIEVAL_INSTRUCTION, null)));

    public EmbeddingPrefixes {
        query = blankToNull(query);
        document = blankToNull(document);
    }

    public static EmbeddingPrefixes defaultsFor(String modelKey) {
        var key = modelKey.toLowerCase(Locale.ROOT);
        return KNOWN_FAMILIES.stream()
                .filter(family -> family.pattern().matcher(key).find())
                .map(KnownFamily::prefixes)
                .findFirst()
                .orElse(NONE);
    }

    public String applyToQuery(String text) {
        return Objects.requireNonNullElse(query, "") + text;
    }

    public String applyToDocument(String text) {
        return Objects.requireNonNullElse(document, "") + text;
    }

    private static Pattern family(String namePattern) {
        return Pattern.compile("(^|[^a-z0-9])" + namePattern + "($|[^a-z0-9])");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record KnownFamily(Pattern pattern, EmbeddingPrefixes prefixes) {}
}
