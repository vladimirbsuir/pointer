package dev.pointer.rag;

import dev.pointer.common.error.ApiException;
import java.util.Arrays;
import java.util.Locale;

enum SearchStrategy {
    VECTOR,
    HYBRID,
    LEXICAL;

    String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    static SearchStrategy fromWireName(String value) {
        return Arrays.stream(values())
                .filter(strategy -> strategy.wireName().equals(value))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("invalid_strategy", "Unknown search strategy: " + value));
    }

    boolean usesVectors() {
        return this != LEXICAL;
    }

    boolean usesLexical() {
        return this != VECTOR;
    }
}
