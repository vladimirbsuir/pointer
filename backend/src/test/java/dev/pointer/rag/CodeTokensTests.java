package dev.pointer.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CodeTokensTests {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "SecretCipher | secretcipher secret cipher",
                "secretCipher | secretcipher secret cipher",
                "SECRET_CIPHER_KEY | secretcipherkey secret cipher key",
                "HTTPServer2Client | httpserver2client http server 2 client",
                "pointer-runner | pointer runner",
                "V18__embedding_prefixes.sql | v18embeddingprefixes v 18 embedding prefixes sql",
                "СоздатьСессию | создатьсессию создать сессию",
                "plain | plain"
            })
    void identifiersKeepTheWholeNameAndItsParts(String text, String expected) {
        assertThat(CodeTokens.expand(text)).isEqualTo(expected);
    }

    @Test
    void longTokensAreDropped() {
        assertThat(CodeTokens.expand("a".repeat(65) + " short")).isEqualTo("short");
    }

    @Test
    void queryTermsSkipStopWordsSingleLettersAndDuplicates() {
        assertThat(CodeTokens.queryTerms("Where do we encrypt the provider API keys? Encrypt!"))
                .containsExactly("encrypt", "provider", "api", "keys");
        assertThat(CodeTokens.queryTerms("где хранится ключ для провайдера"))
                .containsExactly("хранится", "ключ", "провайдера");
    }

    @Test
    void tsQueryIsADisjunction() {
        assertThat(CodeTokens.tsQuery(CodeTokens.queryTerms("SecretCipher encrypt")))
                .isEqualTo("secretcipher | secret | cipher | encrypt");
    }

    @Test
    void emptyInputHasNoTerms() {
        assertThat(CodeTokens.queryTerms("the and of ?")).isEmpty();
        assertThat(CodeTokens.expand(null)).isEmpty();
    }
}
