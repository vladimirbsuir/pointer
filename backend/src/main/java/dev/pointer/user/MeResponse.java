package dev.pointer.user;

import java.util.UUID;

public record MeResponse(
        UUID id, String email, String displayName, String locale, boolean hasPassword, GitHubAccount github) {

    public record GitHubAccount(String login, boolean repositoryAccess) {}
}
