package dev.pointer.auth.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import dev.pointer.IntegrationTest;
import dev.pointer.auth.AuthResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;

@TestPropertySource(
        properties = {"pointer.github.client-id=test-client-id", "pointer.github.client-secret=test-client-secret"})
class GitHubAuthTests extends IntegrationTest {

    private static final String REDIRECT_URI = "http://127.0.0.1:53682/callback";
    private static final String CODE_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    private static final AtomicLong GITHUB_IDS = new AtomicLong(1_000);

    @MockitoBean
    private GitHubClient gitHub;

    @Test
    void authorizationUrlContainsPkceAndLoopbackRedirect() {
        client.get()
                .uri(uri -> uri.path("/api/v1/auth/github/authorize-url")
                        .queryParam("codeChallenge", CODE_CHALLENGE)
                        .queryParam("state", "state-123")
                        .queryParam("redirectUri", REDIRECT_URI)
                        .build())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.url")
                .value(
                        String.class,
                        url -> assertThat(url)
                                .startsWith("https://github.com/login/oauth/authorize?")
                                .contains("client_id=test-client-id")
                                .contains("code_challenge=" + CODE_CHALLENGE)
                                .contains("code_challenge_method=S256")
                                .contains("state=state-123")
                                .contains("redirect_uri=http://127.0.0.1:53682/callback"));
    }

    @Test
    void nonLoopbackRedirectIsRejected() {
        client.get()
                .uri(uri -> uri.path("/api/v1/auth/github/authorize-url")
                        .queryParam("codeChallenge", CODE_CHALLENGE)
                        .queryParam("state", "state-123")
                        .queryParam("redirectUri", "https://evil.example.com/callback")
                        .build())
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("invalid_redirect_uri");
    }

    @Test
    void firstCallbackCreatesAccountAndNextOneSignsIntoIt() {
        var email = uniqueEmail();
        var code = stubGitHubAccount(GITHUB_IDS.incrementAndGet(), "octocat", "The Octocat", email);

        var created = callback(code)
                .expectStatus()
                .isOk()
                .expectBody(AuthResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(created.user().email()).isEqualTo(email);
        assertThat(created.user().displayName()).isEqualTo("The Octocat");
        assertThat(created.user().hasPassword()).isFalse();
        assertThat(created.user().github().login()).isEqualTo("octocat");

        var again = callback(code)
                .expectStatus()
                .isOk()
                .expectBody(AuthResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(again.user().id()).isEqualTo(created.user().id());
    }

    @Test
    void callbackRefusesToTakeOverExistingEmailAccount() {
        var email = uniqueEmail();
        register(email);
        var code = stubGitHubAccount(GITHUB_IDS.incrementAndGet(), "someone", null, email);

        callback(code)
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("account_exists");
    }

    @Test
    void callbackRequiresVerifiedEmail() {
        var code = stubGitHubAccount(GITHUB_IDS.incrementAndGet(), "noemail", null, null);

        callback(code)
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("github_email_unverified");
    }

    @Test
    void signedInUserCanLinkGitHubAndThenSignInWithIt() {
        var email = uniqueEmail();
        var auth = register(email);
        var code = stubGitHubAccount(GITHUB_IDS.incrementAndGet(), "linker", null, uniqueEmail());

        client.post()
                .uri("/api/v1/auth/github/link")
                .header(HttpHeaders.AUTHORIZATION, bearer(auth))
                .body(Map.of("code", code, "codeVerifier", "verifier", "redirectUri", REDIRECT_URI))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.github.login")
                .isEqualTo("linker");

        var signedIn = callback(code)
                .expectStatus()
                .isOk()
                .expectBody(AuthResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(signedIn.user().id()).isEqualTo(auth.user().id());
    }

    @Test
    void gitHubAccountLinkedToOneUserCannotBeLinkedToAnother() {
        var code = stubGitHubAccount(GITHUB_IDS.incrementAndGet(), "taken", null, uniqueEmail());
        callback(code).expectStatus().isOk();
        var other = register(uniqueEmail());

        client.post()
                .uri("/api/v1/auth/github/link")
                .header(HttpHeaders.AUTHORIZATION, bearer(other))
                .body(Map.of("code", code, "codeVerifier", "verifier", "redirectUri", REDIRECT_URI))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("identity_in_use");
    }

    private String stubGitHubAccount(long id, String login, String name, String email) {
        var code = "code-" + id;
        var token = "token-" + id;
        when(gitHub.exchangeCode(eq(code), anyString(), anyString())).thenReturn(token);
        when(gitHub.fetchUser(token)).thenReturn(new GitHubClient.GitHubUser(id, login, name));
        when(gitHub.fetchPrimaryVerifiedEmail(token)).thenReturn(Optional.ofNullable(email));
        return code;
    }

    private RestTestClient.ResponseSpec callback(String code) {
        var body = new HashMap<String, Object>();
        body.put("code", code);
        body.put("codeVerifier", "verifier");
        body.put("redirectUri", REDIRECT_URI);
        body.put("device", device(UUID.randomUUID()));
        return client.post().uri("/api/v1/auth/github/callback").body(body).exchange();
    }
}
