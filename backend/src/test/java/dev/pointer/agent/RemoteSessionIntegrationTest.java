package dev.pointer.agent;

import static dev.pointer.testing.GatewayTestClient.type;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import dev.pointer.container.ContainerRuntime;
import dev.pointer.container.ContainerSpec;
import dev.pointer.container.GitHubRepositories;
import dev.pointer.execution.DeviceChannel;
import dev.pointer.execution.ExecutionService;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

@Import(RemoteSessionIntegrationTest.Runtime.class)
@TestPropertySource(
        properties = {
            "pointer.container.enabled=true",
            "pointer.container.idle-check-interval=1h",
            "pointer.container.max-running=1",
            "pointer.desktop.viewer-buffer=4MB"
        })
abstract class RemoteSessionIntegrationTest extends AgentIntegrationTest {

    static final String REPOSITORY = "octo/demo";
    static final String GITHUB_TOKEN = "gho_test_token";

    @TestConfiguration
    static class Runtime {

        @Bean
        @Primary
        FakeContainerRuntime fakeContainerRuntime() {
            return new FakeContainerRuntime();
        }
    }

    @Autowired
    ContainerRuntime containers;

    @Autowired
    UserService users;

    @Autowired
    SessionWriter writer;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ExecutionService execution;

    @Autowired
    DeviceChannel channel;

    @MockitoBean
    GitHubRepositories gitHub;

    @BeforeEach
    void stopWorkspacesOfEarlierTests() {
        jdbc.sql("update remote_workspaces set status = 'stopped' where status <> 'stopped'")
                .update();
    }

    Setup remoteSetUp() {
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

    void setRepository(Setup setup) {
        client.patch()
                .uri("/api/v1/projects/{id}", setup.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("repository", REPOSITORY))
                .exchange()
                .expectStatus()
                .isOk();
    }

    JsonNode createRemoteSession(Setup setup) {
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

    GatewayTestClient startRunner(Setup setup, List<JsonNode> requests) throws Exception {
        return startRunner(setup, requests, List.of("executor", "viewer"), RemoteSessionIntegrationTest::result);
    }

    GatewayTestClient startRunner(
            Setup setup,
            List<JsonNode> requests,
            List<String> capabilities,
            Function<String, Map<String, Object>> results)
            throws Exception {
        return startRunnerAnswering(
                setup,
                requests,
                capabilities,
                request -> results.apply(request.path("operation").asString()));
    }

    GatewayTestClient startRunnerAnswering(
            Setup setup,
            List<JsonNode> requests,
            List<String> capabilities,
            Function<JsonNode, Map<String, Object>> results)
            throws Exception {
        var spec = fakeRuntime().awaitCreatedForProject(setup.projectId(), TIMEOUT);
        awaitTrue(() -> fakeRuntime().isRunning(spec.name()));
        var deviceId = UUID.fromString(spec.environment().get("POINTER_DEVICE_ID"));
        assertThat(spec.environment())
                .containsEntry("POINTER_PROJECT_ID", setup.projectId().toString());
        var runner = GatewayTestClient.connect(port, runnerToken(spec), deviceId, capabilities, List.of());
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
                            "result", results.apply(request)));
        });
        runner.send(
                "device.projects_changed",
                Map.of("projects", List.of(setup.projectId().toString())));
        return runner;
    }

    static Map<String, Object> result(String operation) {
        return switch (operation) {
            case "pointer.repo_prepare" -> Map.of("branch", "pointer/x", "baseBranch", "main", "head", "000");
            case "pointer.repo_publish" -> Map.of("committed", true, "pushed", true, "commit", "abc1234");
            case "pointer.suggest_files" -> Map.of("paths", List.of("README.md"));
            case "pointer.read_attachment" -> Map.of("content", "# Demo\n");
            case "run_command" -> Map.of("exitCode", 0, "output", "ok\n", "truncated", false);
            default -> Map.of();
        };
    }

    String runnerToken(ContainerSpec spec) {
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

    JsonNode awaitWorkspace(Setup setup, UUID sessionId, Predicate<JsonNode> condition) {
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

    FakeContainerRuntime fakeRuntime() {
        return (FakeContainerRuntime) containers;
    }

    static void awaitTrue(java.util.function.BooleanSupplier condition) {
        var deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep();
        }
        throw new AssertionError("Condition not met within " + TIMEOUT);
    }

    static void sleep() {
        try {
            Thread.sleep(Duration.ofMillis(100));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
