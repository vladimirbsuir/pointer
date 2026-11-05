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
                            "body", request.body()))
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
}
