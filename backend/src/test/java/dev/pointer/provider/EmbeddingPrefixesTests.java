package dev.pointer.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EmbeddingPrefixesTests {

    @ParameterizedTest
    @CsvSource(
            nullValues = "null",
            value = {
                "nomic-embed-text, 'search_query: ', 'search_document: '",
                "nomic-embed-text:latest, 'search_query: ', 'search_document: '",
                "intfloat/multilingual-e5-large, 'query: ', 'passage: '",
                "BAAI/bge-large-en-v1.5, 'Represent this sentence for searching relevant passages: ', null",
                "mxbai-embed-large, 'Represent this sentence for searching relevant passages: ', null",
                "bge-m3, null, null",
                "text-embedding-3-small, null, null",
                "gpt-5, null, null"
            })
    void defaultsDependOnTheModelFamily(String modelKey, String query, String document) {
        assertThat(EmbeddingPrefixes.defaultsFor(modelKey)).isEqualTo(new EmbeddingPrefixes(query, document));
    }

    @ParameterizedTest
    @CsvSource(value = {"'', ''", "'  ', ''"})
    void blankPrefixesAreNone(String query, String document) {
        var prefixes = new EmbeddingPrefixes(query, document);

        assertThat(prefixes).isEqualTo(EmbeddingPrefixes.NONE);
        assertThat(prefixes.applyToQuery("find sessions")).isEqualTo("find sessions");
    }

    @ParameterizedTest
    @CsvSource(value = {"'search_query: ', 'search_document: '"})
    void prefixesArePrependedVerbatim(String query, String document) {
        var prefixes = new EmbeddingPrefixes(query, document);

        assertThat(prefixes.applyToQuery("find sessions")).isEqualTo("search_query: find sessions");
        assertThat(prefixes.applyToDocument("class Session")).isEqualTo("search_document: class Session");
    }
}
