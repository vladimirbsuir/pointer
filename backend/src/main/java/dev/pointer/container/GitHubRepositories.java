package dev.pointer.container;

import java.util.List;

public interface GitHubRepositories {

    Repository repository(String accessToken, String fullName);

    List<Repository> list(String accessToken);

    String openPullRequest(String accessToken, PullRequest request);

    Issue issue(String accessToken, String repository, int number);

    List<IssueComment> issueComments(String accessToken, String repository, int number, int limit);

    long createIssueComment(String accessToken, String repository, int number, String body);

    void updateIssueComment(String accessToken, String repository, long commentId, String body);

    long createHook(String accessToken, String repository, Hook hook);

    void deleteHook(String accessToken, String repository, long hookId);

    record Repository(String fullName, String defaultBranch, String cloneUrl, boolean isPrivate) {}

    record PullRequest(String repository, String head, String base, String title, String body, boolean draft) {

        public PullRequest(String repository, String head, String base, String title, String body) {
            this(repository, head, base, title, body, false);
        }
    }

    record Issue(int number, String title, String body, String author, String url, boolean isPullRequest) {}

    record IssueComment(String author, String body) {}

    record Hook(String url, String secret, List<String> events) {}
}
