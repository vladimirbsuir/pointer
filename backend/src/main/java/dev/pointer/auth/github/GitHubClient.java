package dev.pointer.auth.github;

import java.util.Optional;

public interface GitHubClient {

    String exchangeCode(String code, String codeVerifier, String redirectUri);

    GitHubUser fetchUser(String accessToken);

    Optional<String> fetchPrimaryVerifiedEmail(String accessToken);

    record GitHubUser(long id, String login, String name) {}
}
