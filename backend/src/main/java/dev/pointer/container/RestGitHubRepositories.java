package dev.pointer.container;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.pointer.common.error.ApiException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class RestGitHubRepositories implements GitHubRepositories {

    private static final String API_VERSION = "2022-11-28";
    private static final ParameterizedTypeReference<List<RepositoryBody>> REPOSITORY_LIST =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<PullRequestBody>> PULL_REQUEST_LIST =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<CommentBody>> COMMENT_LIST =
            new ParameterizedTypeReference<>() {};
    private static final int MAX_PAGE_SIZE = 100;

    private final RestClient client;

    RestGitHubRepositories(String apiUrl) {
        this.client = RestClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader("X-GitHub-Api-Version", API_VERSION)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .build();
    }

    @Override
    public Repository repository(String accessToken, String fullName) {
        var body = call(() -> client.get()
                .uri("/repos/" + fullName)
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .retrieve()
                .body(RepositoryBody.class));
        if (body == null) {
            throw ApiException.notFound("repository_not_found", "GitHub repository does not exist");
        }
        return body.toRepository();
    }

    @Override
    public List<Repository> list(String accessToken) {
        var body = call(() -> client.get()
                .uri("/user/repos?per_page=100&sort=updated")
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .retrieve()
                .body(REPOSITORY_LIST));
        return body == null
                ? List.of()
                : body.stream().map(RepositoryBody::toRepository).toList();
    }

    @Override
    public String openPullRequest(String accessToken, PullRequest request) {
        try {
            var created = client.post()
                    .uri("/repos/" + request.repository() + "/pulls")
                    .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "title", request.title(),
                            "head", request.head(),
                            "base", request.base(),
                            "body", request.body(),
                            "draft", request.draft()))
                    .retrieve()
                    .body(PullRequestBody.class);
            return created == null ? null : created.htmlUrl();
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode() == HttpStatus.UNPROCESSABLE_CONTENT) {
                return existingPullRequest(accessToken, request);
            }
            throw failure(exception);
        } catch (RestClientException exception) {
            throw ApiException.unavailable("github_unavailable", "GitHub request failed");
        }
    }

    @Override
    public Issue issue(String accessToken, String repository, int number) {
        try {
            var body = client.get()
                    .uri("/repos/" + repository + "/issues/" + number)
                    .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                    .retrieve()
                    .body(IssueBody.class);
            if (body == null) {
                throw issueNotFound(number);
            }
            return body.toIssue();
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND || exception.getStatusCode() == HttpStatus.GONE) {
                throw issueNotFound(number);
            }
            throw failure(exception);
        } catch (RestClientException exception) {
            throw ApiException.unavailable("github_unavailable", "GitHub request failed");
        }
    }

    @Override
    public List<IssueComment> issueComments(String accessToken, String repository, int number, int limit) {
        var pageSize = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        var body = call(() -> client.get()
                .uri("/repos/" + repository + "/issues/" + number + "/comments?per_page=" + pageSize)
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .retrieve()
                .body(COMMENT_LIST));
        return body == null
                ? List.of()
                : body.stream().limit(limit).map(CommentBody::toComment).toList();
    }

    @Override
    public long createIssueComment(String accessToken, String repository, int number, String body) {
        var created = call(() -> client.post()
                .uri("/repos/" + repository + "/issues/" + number + "/comments")
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("body", body))
                .retrieve()
                .body(IdBody.class));
        return requireId(created);
    }

    @Override
    public void updateIssueComment(String accessToken, String repository, long commentId, String body) {
        call(() -> client.patch()
                .uri("/repos/" + repository + "/issues/comments/" + commentId)
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("body", body))
                .retrieve()
                .toBodilessEntity());
    }

    @Override
    public long createHook(String accessToken, String repository, Hook hook) {
        var config = Map.of("url", hook.url(), "content_type", "json", "secret", hook.secret(), "insecure_ssl", "0");
        try {
            var created = client.post()
                    .uri("/repos/" + repository + "/hooks")
                    .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("name", "web", "active", true, "events", hook.events(), "config", config))
                    .retrieve()
                    .body(IdBody.class);
            return requireId(created);
        } catch (HttpClientErrorException exception) {
            throw switch (exception.getStatusCode().value()) {
                case 422 ->
                    ApiException.conflict(
                            "webhook_rejected", "GitHub rejected the webhook; check the public URL of the server");
                case 403, 404 ->
                    ApiException.forbidden(
                            "webhook_forbidden", "Your GitHub account cannot manage webhooks of this repository");
                default -> failure(exception);
            };
        } catch (RestClientException exception) {
            throw ApiException.unavailable("github_unavailable", "GitHub request failed");
        }
    }

    @Override
    public void deleteHook(String accessToken, String repository, long hookId) {
        try {
            client.delete()
                    .uri("/repos/" + repository + "/hooks/" + hookId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode() != HttpStatus.NOT_FOUND) {
                throw failure(exception);
            }
        } catch (RestClientException exception) {
            throw ApiException.unavailable("github_unavailable", "GitHub request failed");
        }
    }

    private static long requireId(IdBody body) {
        if (body == null) {
            throw ApiException.unavailable("github_unavailable", "GitHub returned an empty response");
        }
        return body.id();
    }

    private static ApiException issueNotFound(int number) {
        return ApiException.notFound("issue_not_found", "Issue #" + number + " does not exist in the repository");
    }

    private String existingPullRequest(String accessToken, PullRequest request) {
        var owner = request.repository().substring(0, request.repository().indexOf('/'));
        var open = call(() -> client.get()
                .uri("/repos/" + request.repository() + "/pulls?state=open&head=" + owner + ":" + request.head())
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .retrieve()
                .body(PULL_REQUEST_LIST));
        if (open == null || open.isEmpty()) {
            throw ApiException.conflict("pull_request_failed", "GitHub could not open a pull request for this branch");
        }
        return open.getFirst().htmlUrl();
    }

    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (HttpClientErrorException exception) {
            throw failure(exception);
        } catch (RestClientException exception) {
            throw ApiException.unavailable("github_unavailable", "GitHub request failed");
        }
    }

    private static ApiException failure(HttpClientErrorException exception) {
        return switch (exception.getStatusCode().value()) {
            case 401 ->
                ApiException.unauthorized(
                        "github_token_invalid", "GitHub rejected the token. Allow repository access again in settings");
            case 403, 404 ->
                ApiException.notFound("repository_not_found", "GitHub repository does not exist or is not accessible");
            default -> ApiException.unavailable("github_unavailable", "GitHub request failed");
        };
    }

    private static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    record RepositoryBody(
            @JsonProperty("full_name") String fullName,
            @JsonProperty("default_branch") String defaultBranch,
            @JsonProperty("clone_url") String cloneUrl,
            @JsonProperty("private") boolean isPrivate) {

        Repository toRepository() {
            return new Repository(fullName, defaultBranch, cloneUrl, isPrivate);
        }
    }

    record PullRequestBody(@JsonProperty("html_url") String htmlUrl) {}

    record IdBody(long id) {}

    record UserBody(String login) {}

    record IssueBody(
            int number,
            String title,
            String body,
            UserBody user,
            @JsonProperty("html_url") String htmlUrl,
            @JsonProperty("pull_request") Map<String, Object> pullRequest) {

        Issue toIssue() {
            return new Issue(number, title, body == null ? "" : body, login(user), htmlUrl, pullRequest != null);
        }
    }

    record CommentBody(UserBody user, String body) {

        IssueComment toComment() {
            return new IssueComment(login(user), body == null ? "" : body);
        }
    }

    private static String login(UserBody user) {
        return user == null || user.login() == null ? "unknown" : user.login();
    }
}
