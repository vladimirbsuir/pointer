package dev.pointer.agent;

import static dev.pointer.testing.GatewayTestClient.type;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import dev.pointer.container.ContainerRuntime;
import dev.pointer.container.ContainerSpec;
import dev.pointer.container.GitHubRepositories;
import dev.pointer.session.SessionStatus;
import dev.pointer.session.SessionWriter;
import dev.pointer.testing.FakeContainerRuntime;
import dev.pointer.testing.GatewayTestClient;
import dev.pointer.user.UserIdentity;
import dev.pointer.user.UserService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

@TestPropertySource(
        properties = {
            "pointer.container.enabled=true",
            "pointer.container.idle-check-interval=1h",
            "pointer.container.max-running=1"
        })
class RemoteExecutionTests extends AgentIntegrationTest {

    private static final String REPOSITORY = "octo/demo";
    private static final String GITHUB_TOKEN = "gho_test_token";

    @TestConfiguration
    static class Runtime {

        @Bean
        @Primary
        FakeContainerRuntime fakeContainerRuntime() {
            return new FakeContainerRuntime();
        }
    }

    @Autowired
    private ContainerRuntime containers;

    @Autowired
    private UserService users;

    @Autowired
    private SessionWriter writer;

    @Autowired
    private JdbcClient jdbc;

    @MockitoBean
    private GitHubRepositories gitHub;

    @BeforeEach
    void stopWorkspacesOfEarlierTests() {
        jdbc.sql("update remote_workspaces set status = 'stopped' where status <> 'stopped'")
                .update();
    }

    @Test
    void remoteSessionClonesRunsAutoApprovedToolsAndPushesAfterTheTurn() throws Exception {
        var setup = remoteSetUp();
        llm.enqueueToolCall("call_1", "run_command", "{\"command\":\"npm test\"}");
        llm.enqueueText("Tests pass.");

        var created = createRemoteSession(setup);
        var sessionId = UUID.fromString(created.path("session").path("id").asString());
        assertThat(created.path("session").path("autoApprove").asBoolean()).isTrue();
        assertThat(created.path("workspace").path("branch").asString())
                .isEqualTo("pointer/" + sessionId.toString().substring(0, 8));

        var requests = new CopyOnWriteArrayList<JsonNode>();
        try (var runner = startRunner(setup, requests)) {
            awaitWorkspace(
                    setup,
                    sessionId,
                    workspace -> "ready".equals(workspace.path("status").asString()));
            var prepare = requests.getFirst();
            assertThat(prepare.path("operation").asString()).isEqualTo("pointer.repo_prepare");
            assertThat(prepare.path("arguments").path("cloneUrl").asString())
                    .isEqualTo("https://github.com/" + REPOSITORY + ".git");
            assertThat(prepare.path("arguments").path("token").asString()).isEqualTo(GITHUB_TOKEN);
            assertThat(prepare.path("arguments").path("baseBranch").asString()).isEqualTo("main");

            client.post()
                    .uri("/api/v1/sessions/{id}/messages", sessionId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                    .body(Map.of("text", "Run the tests\nand report"))
                    .exchange()
                    .expectStatus()
                    .isAccepted();

            var workspace = awaitWorkspace(
                    setup, sessionId, current -> !current.path("pushedCommit").isNull());
            assertThat(workspace.path("pushedCommit").asString()).isEqualTo("abc1234");
            assertThat(workspace.path("branchUrl").asString())
                    .isEqualTo("https://github.com/" + REPOSITORY + "/tree/pointer/"
                            + sessionId.toString().substring(0, 8));
        }

        assertThat(requests.stream()
                        .map(request -> request.path("operation").asString())
                        .filter(operation -> !operation.equals("pointer.project_context")))
                .containsExactly("pointer.repo_prepare", "run_command", "pointer.repo_publish");
        var publish = requests.getLast();
        assertThat(publish.path("arguments").path("message").asString()).isEqualTo("Run the tests");
        assertThat(publish.path("arguments").path("token").asString()).isEqualTo(GITHUB_TOKEN);

        when(gitHub.openPullRequest(eq(GITHUB_TOKEN), any()))
                .thenReturn("https://github.com/" + REPOSITORY + "/pull/7");
        client.post()
                .uri("/api/v1/sessions/{id}/remote/pull-request", sessionId)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.url")
                .isEqualTo("https://github.com/" + REPOSITORY + "/pull/7");
    }

    @Test
    void fileSuggestionsAndContentComeFromTheRunner() throws Exception {
        var setup = remoteSetUp();
        var created = createRemoteSession(setup);
        var sessionId = UUID.fromString(created.path("session").path("id").asString());

        try (var runner = startRunner(setup, new CopyOnWriteArrayList<>())) {
            awaitWorkspace(
                    setup,
                    sessionId,
                    workspace -> "ready".equals(workspace.path("status").asString()));
            client.get()
                    .uri("/api/v1/sessions/{id}/remote/files?query=read", sessionId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.paths[0]")
                    .isEqualTo("README.md");
            client.get()
                    .uri("/api/v1/sessions/{id}/remote/file?path=README.md", sessionId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.content")
                    .isEqualTo("# Demo\n");
        }
    }

    @Test
    void runnerTokensAreLimitedToTheGatewayAndRevokedWithTheSession() throws Exception {
        var setup = remoteSetUp();
        var created = createRemoteSession(setup);
        var sessionId = UUID.fromString(created.path("session").path("id").asString());
        var spec = fakeRuntime().awaitCreated(TIMEOUT);
        var deviceId = UUID.fromString(spec.environment().get("POINTER_DEVICE_ID"));

        client.post()
                .uri("/api/v1/runner/token")
                .body(Map.of("deviceId", deviceId.toString(), "secret", "wrong"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
        var token = runnerToken(spec);
        client.get()
                .uri("/api/v1/projects")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isForbidden();

        client.get()
                .uri("/api/v1/projects/{id}/remote-sessions", setup.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].sessionId")
                .isEqualTo(sessionId.toString());

        client.delete()
                .uri("/api/v1/sessions/{id}", sessionId)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectStatus()
                .isNoContent();
        awaitTrue(() -> fakeRuntime().removed().contains(spec.name()));
        awaitTrue(() -> client.post()
                .uri("/api/v1/runner/token")
                .body(Map.of(
                        "deviceId",
                        deviceId.toString(),
                        "secret",
                        spec.environment().get("POINTER_RUNNER_SECRET")))
                .exchange()
                .returnResult()
                .getStatus()
                .equals(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void idleContainerFreesTheOnlySlotWhileBusyOnesQueueNewSessions() throws Exception {
        var setup = remoteSetUp();
        var userId = setup.auth().user().id();
        var first = UUID.fromString(
                createRemoteSession(setup).path("session").path("id").asString());

        try (var runner = startRunner(setup, new CopyOnWriteArrayList<>())) {
            awaitWorkspace(
                    setup,
                    first,
                    workspace -> "ready".equals(workspace.path("status").asString()));
            writer.changeStatus(userId, first, SessionStatus.RUNNING);

            var second = UUID.fromString(
                    createRemoteSession(setup).path("session").path("id").asString());
            awaitWorkspace(
                    setup,
                    second,
                    workspace -> "queued".equals(workspace.path("status").asString()));
            assertThat(fakeRuntime().isRunning("pointer-session-" + first)).isTrue();

            writer.changeStatus(userId, first, SessionStatus.IDLE);
            var secondContainer = fakeRuntime().awaitCreated(TIMEOUT);
            assertThat(secondContainer.name()).isEqualTo("pointer-session-" + second);
            awaitWorkspace(
                    setup,
                    first,
                    workspace -> "stopped".equals(workspace.path("status").asString()));
            assertThat(fakeRuntime().isRunning("pointer-session-" + first)).isFalse();
            awaitTrue(() -> fakeRuntime().isRunning(secondContainer.name()));
        }
    }

    @Test
    void remoteSessionsNeedRepositoryAccessAndARepository() {
        var setup = setUp(false);
        client.post()
                .uri("/api/v1/projects/{id}/remote-sessions", setup.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of())
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("project_repository_missing");

        setRepository(setup);
        client.post()
                .uri("/api/v1/projects/{id}/remote-sessions", setup.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of())
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("github_repository_access_required");
    }

    private Setup remoteSetUp() {
        var setup = setUp(false);
        setRepository(setup);
        var userId = setup.auth().user().id();
        var identity = users.linkIdentity(
                userId,
                UserIdentity.GITHUB,
                Long.toString(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)),
                "octo");
        users.storeIdentityToken(identity, "octo", GITHUB_TOKEN, "read:user,user:email,repo");
        when(gitHub.repository(GITHUB_TOKEN, REPOSITORY))
                .thenReturn(new GitHubRepositories.Repository(
                        REPOSITORY, "main", "https://github.com/" + REPOSITORY + ".git", true));
        return setup;
    }

    private void setRepository(Setup setup) {
        client.patch()
                .uri("/api/v1/projects/{id}", setup.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("repository", REPOSITORY))
                .exchange()
                .expectStatus()
                .isOk();
    }

    private JsonNode createRemoteSession(Setup setup) {
        var modelId = client.get()
                .uri("/api/v1/sessions/{id}", setup.sessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody()
                .path("modelId")
                .asString();
        return client.post()
                .uri("/api/v1/projects/{id}/remote-sessions", setup.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("modelId", modelId))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    private GatewayTestClient startRunner(Setup setup, List<JsonNode> requests) throws Exception {
        var spec = fakeRuntime().awaitCreated(TIMEOUT);
        awaitTrue(() -> fakeRuntime().isRunning(spec.name()));
        var deviceId = UUID.fromString(spec.environment().get("POINTER_DEVICE_ID"));
        assertThat(spec.environment())
                .containsEntry("POINTER_PROJECT_ID", setup.projectId().toString());
        var runner =
                GatewayTestClient.connect(port, runnerToken(spec), deviceId, List.of("executor", "viewer"), List.of());
        runner.onMessage(message -> {
            if (!type(message).equals("exec.request")) {
                return;
            }
            var request = message.path("payload");
            requests.add(request);
            runner.send(
                    "exec.result",
                    Map.of(
                            "callId", request.path("callId").asString(),
                            "status", "succeeded",
                            "result", result(request.path("operation").asString())));
        });
        runner.send(
                "device.projects_changed",
                Map.of("projects", List.of(setup.projectId().toString())));
        return runner;
    }

    private static Map<String, Object> result(String operation) {
        return switch (operation) {
            case "pointer.repo_prepare" -> Map.of("branch", "pointer/x", "baseBranch", "main", "head", "000");
            case "pointer.repo_publish" -> Map.of("committed", true, "pushed", true, "commit", "abc1234");
            case "pointer.suggest_files" -> Map.of("paths", List.of("README.md"));
            case "pointer.read_attachment" -> Map.of("content", "# Demo\n");
            case "run_command" -> Map.of("exitCode", 0, "output", "ok\n", "truncated", false);
            default -> Map.of();
        };
    }

    private String runnerToken(ContainerSpec spec) {
        return client.post()
                .uri("/api/v1/runner/token")
                .body(Map.of(
                        "deviceId", spec.environment().get("POINTER_DEVICE_ID"),
                        "secret", spec.environment().get("POINTER_RUNNER_SECRET")))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody()
                .path("accessToken")
                .asString();
    }

    private JsonNode awaitWorkspace(Setup setup, UUID sessionId, Predicate<JsonNode> condition) {
        var deadline = Instant.now().plus(TIMEOUT);
        JsonNode workspace = null;
        while (Instant.now().isBefore(deadline)) {
            workspace = client.get()
                    .uri("/api/v1/sessions/{id}/remote", sessionId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                    .exchange()
                    .expectBody(JsonNode.class)
                    .returnResult()
                    .getResponseBody();
            if (condition.test(workspace)) {
                return workspace;
            }
            sleep();
        }
        throw new AssertionError("Workspace did not reach the expected state: " + workspace);
    }

    private FakeContainerRuntime fakeRuntime() {
        return (FakeContainerRuntime) containers;
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) {
        var deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep();
        }
        throw new AssertionError("Condition not met within " + TIMEOUT);
    }

    private static void sleep() {
        try {
            Thread.sleep(Duration.ofMillis(100));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
