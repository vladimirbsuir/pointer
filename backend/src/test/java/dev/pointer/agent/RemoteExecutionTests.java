package dev.pointer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import dev.pointer.session.SessionStatus;
import dev.pointer.testing.GatewayTestClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

class RemoteExecutionTests extends RemoteSessionIntegrationTest {

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
                    .isEqualTo("http://host.docker.internal:8080/api/v1/runner/git/" + REPOSITORY + ".git");
            assertThat(prepare.path("arguments").has("token")).isFalse();
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
        assertThat(publish.path("arguments").has("token")).isFalse();

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
    void outdatedContainerIsRecreatedOnTheSameVolumeWhenTheSessionWakes() throws Exception {
        var setup = remoteSetUp();
        llm.enqueueToolCall("call_1", "run_command", "{\"command\":\"ls\"}");
        llm.enqueueText("Done.");
        var sessionId = UUID.fromString(
                createRemoteSession(setup).path("session").path("id").asString());
        var name = "pointer-session-" + sessionId;

        try (var runner = startRunner(setup, new CopyOnWriteArrayList<>())) {
            awaitWorkspace(
                    setup,
                    sessionId,
                    workspace -> "ready".equals(workspace.path("status").asString()));
            fakeRuntime().stop(name);
            jdbc.sql("update remote_workspaces set status = 'stopped' where session_id = ?")
                    .param(sessionId)
                    .update();
            fakeRuntime().markOutdated(name);

            client.post()
                    .uri("/api/v1/sessions/{id}/messages", sessionId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                    .body(Map.of("text", "List the files"))
                    .exchange()
                    .expectStatus()
                    .isAccepted();

            awaitTrue(() -> fakeRuntime().createdNamed(name).size() == 2);
            awaitWorkspace(
                    setup,
                    sessionId,
                    workspace -> "ready".equals(workspace.path("status").asString()));
        }

        assertThat(fakeRuntime().removedContainers()).contains(name);
        assertThat(fakeRuntime().removed()).doesNotContain(name);
        var specs = fakeRuntime().createdNamed(name);
        assertThat(specs.getLast().volume()).isEqualTo(specs.getFirst().volume());
        assertThat(specs.getLast().environment().get("POINTER_RUNNER_SECRET"))
                .isNotEqualTo(specs.getFirst().environment().get("POINTER_RUNNER_SECRET"));
    }

    @Test
    void aRunnersDesktopCapabilityIsKeptStoredAndUsedWhileItIsOffline() throws Exception {
        var setup = remoteSetUp();
        var sessionId = UUID.fromString(
                createRemoteSession(setup).path("session").path("id").asString());
        var spec = fakeRuntime().awaitCreatedNamed("pointer-session-" + sessionId, TIMEOUT);
        var deviceId = UUID.fromString(spec.environment().get("POINTER_DEVICE_ID"));
        assertThat(execution.capabilities(deviceId)).containsExactly("executor");

        try (var runner = GatewayTestClient.connect(
                port, runnerToken(spec), deviceId, List.of("executor", "desktop", "viewer", "relay"), List.of())) {
            awaitTrue(() -> execution.capabilities(deviceId).contains("desktop"));
            assertThat(execution.capabilities(deviceId)).containsExactlyInAnyOrder("executor", "desktop");
        }

        awaitTrue(() -> !channel.isConnected(deviceId));
        assertThat(execution.capabilities(deviceId)).containsExactlyInAnyOrder("executor", "desktop");
        assertThat(jdbc.sql("select array_to_string(capabilities, ',') from devices where id = ?")
                        .param(deviceId)
                        .query(String.class)
                        .single())
                .isEqualTo("desktop,executor");
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
        var spec = fakeRuntime().awaitCreatedNamed("pointer-session-" + sessionId, TIMEOUT);
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
            var secondContainer = fakeRuntime().awaitCreatedNamed("pointer-session-" + second, TIMEOUT);
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
}
