package dev.pointer.container;

import java.util.List;

public interface GitHubRepositories {

    Repository repository(String accessToken, String fullName);

    List<Repository> list(String accessToken);

    String openPullRequest(String accessToken, PullRequest request);

    record Repository(String fullName, String defaultBranch, String cloneUrl, boolean isPrivate) {}

    record PullRequest(String repository, String head, String base, String title, String body) {}
}
