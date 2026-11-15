package dev.pointer.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.pointer.common.error.ApiException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RestGitHubRepositoriesTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TOKEN = "gho_token";
    private static final String REPOSITORY = "octo/demo";

    private final Map<String, Response> responses = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private RestGitHubRepositories gitHub;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var key = exchange.getRequestMethod() + " " + exchange.getRequestURI();
            requests.add(new Request(key, exchange.getRequestHeaders().getFirst("Authorization"), body));
            var response = responses.getOrDefault(key, new Response(404, "{\"message\":\"Not Found\"}"));
            var bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        gitHub = new RestGitHubRepositories(
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void issueIsReadWithItsAuthorAndPullRequestsAreRecognised() {
        respond("GET /repos/octo/demo/issues/5", 200, """
                {"number":5,"title":"Crash","body":null,"user":{"login":"alice"},
                 "html_url":"https://github.com/octo/demo/issues/5"}""");
        respond("GET /repos/octo/demo/issues/6", 200, """
                {"number":6,"title":"A change","body":"text","user":{"login":"bob"},
                 "html_url":"https://github.com/octo/demo/pull/6","pull_request":{"url":"x"}}""");

        var issue = gitHub.issue(TOKEN, REPOSITORY, 5);

        assertThat(issue)
                .isEqualTo(new GitHubRepositories.Issue(
                        5, "Crash", "", "alice", "https://github.com/octo/demo/issues/5", false));
        assertThat(gitHub.issue(TOKEN, REPOSITORY, 6).isPullRequest()).isTrue();
        assertThat(requests.getFirst().authorization()).isEqualTo("Bearer " + TOKEN);
        assertThatThrownBy(() -> gitHub.issue(TOKEN, REPOSITORY, 404))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("issue_not_found"));
    }

    @Test
    void issueCommentsAreListedCreatedAndUpdated() {
        respond("GET /repos/octo/demo/issues/5/comments?per_page=2", 200, """
                [{"user":{"login":"alice"},"body":"first"},{"user":null,"body":null},{"user":{"login":"c"},"body":"x"}]""");
        respond("POST /repos/octo/demo/issues/5/comments", 201, "{\"id\":991}");
        respond("PATCH /repos/octo/demo/issues/comments/991", 200, "{\"id\":991}");

        assertThat(gitHub.issueComments(TOKEN, REPOSITORY, 5, 2))
                .containsExactly(
                        new GitHubRepositories.IssueComment("alice", "first"),
                        new GitHubRepositories.IssueComment("unknown", ""));
        assertThat(gitHub.createIssueComment(TOKEN, REPOSITORY, 5, "status")).isEqualTo(991);
        gitHub.updateIssueComment(TOKEN, REPOSITORY, 991, "done");

        assertThat(body("POST /repos/octo/demo/issues/5/comments").path("body").asString())
                .isEqualTo("status");
        assertThat(body("PATCH /repos/octo/demo/issues/comments/991")
                        .path("body")
                        .asString())
                .isEqualTo("done");
    }

    @Test
    void hooksAreCreatedForIssueEventsAndDeleted() {
        respond("POST /repos/octo/demo/hooks", 201, "{\"id\":42}");
        respond("DELETE /repos/octo/demo/hooks/42", 204, "");

        var hookId = gitHub.createHook(
                TOKEN,
                REPOSITORY,
                new GitHubRepositories.Hook("https://pointer.example/hook", "s3cret", List.of("issues")));
        gitHub.deleteHook(TOKEN, REPOSITORY, 42);
        gitHub.deleteHook(TOKEN, REPOSITORY, 43);

        assertThat(hookId).isEqualTo(42);
        var created = body("POST /repos/octo/demo/hooks");
        assertThat(created.path("name").asString()).isEqualTo("web");
        assertThat(created.path("events").get(0).asString()).isEqualTo("issues");
        assertThat(created.path("config").path("url").asString()).isEqualTo("https://pointer.example/hook");
        assertThat(created.path("config").path("content_type").asString()).isEqualTo("json");
        assertThat(created.path("config").path("secret").asString()).isEqualTo("s3cret");
    }

    @Test
    void hookErrorsExplainMissingPermissionsAndRejectedUrls() {
        var hook = new GitHubRepositories.Hook("http://localhost/hook", "s3cret", List.of("issues"));
        assertThatThrownBy(() -> gitHub.createHook(TOKEN, REPOSITORY, hook))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("webhook_forbidden"));

        respond("POST /repos/octo/demo/hooks", 422, "{\"message\":\"Validation Failed\"}");
        assertThatThrownBy(() -> gitHub.createHook(TOKEN, REPOSITORY, hook))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("webhook_rejected"));
    }

    @Test
    void draftPullRequestsCarryTheDraftFlag() {
        respond("POST /repos/octo/demo/pulls", 201, "{\"html_url\":\"https://github.com/octo/demo/pull/3\"}");

        var url = gitHub.openPullRequest(
                TOKEN,
                new GitHubRepositories.PullRequest(REPOSITORY, "pointer/issue-5", "main", "Crash", "Refs #5", true));

        assertThat(url).isEqualTo("https://github.com/octo/demo/pull/3");
        var created = body("POST /repos/octo/demo/pulls");
        assertThat(created.path("draft").asBoolean()).isTrue();
        assertThat(created.path("head").asString()).isEqualTo("pointer/issue-5");
        assertThat(created.path("body").asString()).isEqualTo("Refs #5");
    }

    private void respond(String request, int status, String body) {
        responses.put(request, new Response(status, body));
    }

    private JsonNode body(String request) {
        return requests.stream()
                .filter(sent -> sent.key().equals(request))
                .findFirst()
                .map(sent -> JSON.readTree(sent.body()))
                .orElseThrow();
    }

    private record Response(int status, String body) {}

    private record Request(String key, String authorization, String body) {}
}
