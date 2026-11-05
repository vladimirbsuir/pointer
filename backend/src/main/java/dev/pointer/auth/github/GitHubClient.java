package dev.pointer.auth.github;

import java.util.Arrays;
import java.util.Optional;

public interface GitHubClient {

    TokenGrant exchangeCode(String code, String codeVerifier, String redirectUri);

    GitHubUser fetchUser(String accessToken);

    Optional<String> fetchPrimaryVerifiedEmail(String accessToken);

    record GitHubUser(long id, String login, String name) {}

    record TokenGrant(String accessToken, String scope) {

        public boolean includesRepositories() {
            return scope != null && Arrays.asList(scope.split("[ ,]+")).contains("repo");
        }
    }
}
