package dev.pointer.agent;

import static dev.pointer.testing.GatewayTestClient.type;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.pointer.container.ContainerRuntime;
import dev.pointer.container.ContainerSpec;
import dev.pointer.container.GitHubRepositories;
import dev.pointer.provider.ProviderDtos;
import dev.pointer.testing.FakeContainerRuntime;
import dev.pointer.testing.FakeTelegramServer;
import dev.pointer.testing.GatewayTestClient;
import dev.pointer.user.UserIdentity;
import dev.pointer.user.UserService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

@TestPropertySource(
        properties = {
            "pointer.container.enabled=true",
            "pointer.container.idle-check-interval=1h",
            "pointer.container.max-running=4",
            "pointer.automation.watch-interval=200ms",
            "pointer.automation.stall-grace=1s",
            "pointer.public-url=https://pointer.example/"
        })
class AutomationFlowTests extends AgentIntegrationTest {

    private static final String REPOSITORY = "octo/demo";
    private static final String GITHUB_TOKEN = "gho_test_token";
    private static final String CHECK_COMMAND = "make check";
    private static final String PULL_REQUEST_URL = "https://github.com/" + REPOSITORY + "/pull/9";
    private static final int ISSUE = 5;
    private static final long STATUS_COMMENT = 77;
    private static final long WEBHOOK = 55;
    private static final FakeTelegramServer TELEGRAM = new FakeTelegramServer();

    @DynamicPropertySource
    static void telegram(DynamicPropertyRegistry registry) {
        registry.add("pointer.telegram.bot-token", () -> FakeTelegramServer.TOKEN);
        registry.add("pointer.telegram.api-url", TELEGRAM::apiUrl);
        registry.add("pointer.telegram.poll-timeout", () -> "1s");
    }

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
    private JdbcClient jdbc;

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private GitHubRepositories gitHub;

    private final List<JsonNode> runnerRequests = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Map<String, Object>> checkResults = new ConcurrentLinkedQueue<>();
    private volatile boolean pushes = true;

    @BeforeEach
    void stopWorkspacesOfEarlierTests() {
        jdbc.sql("update remote_workspaces set status = 'stopped' where status <> 'stopped'")
                .update();
    }

    @Test
    void manualRunPassesTheCheckAndOpensAPullRequest() throws Exception {
        var setup = automationSetUp(Map.of());
        assertThat(automation(setup).path("webhook").asString()).isEqualTo("created");
        var hook = ArgumentCaptor.forClass(GitHubRepositories.Hook.class);
        verify(gitHub).createHook(eq(GITHUB_TOKEN), eq(REPOSITORY), hook.capture());
        assertThat(hook.getValue().url()).startsWith("https://pointer.example/api/v1/webhooks/github/");
        assertThat(hook.getValue().events()).containsExactly("issues");
        llm.enqueueText("Fixed the null check in the parser.");

        var started = startRun(setup)
                .expectStatus()
                .isCreated()
                .expectBody(JsonNode.class)
                .returnResult();
        assertThat(started.getResponseBody().path("status").asString()).isEqualTo("queued");
        startRun(setup)
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("run_already_active");

        try (var runner = startRunner(setup)) {
            var run = awaitRun(
                    setup, current -> "succeeded".equals(current.path("status").asString()));
            assertThat(run.path("attempts").asInt()).isEqualTo(1);
            assertThat(run.path("trigger").asString()).isEqualTo("desktop");
            assertThat(run.path("pullRequestUrl").asString()).isEqualTo(PULL_REQUEST_URL);
            assertThat(run.path("issueUrl").asString()).isEqualTo("https://github.com/" + REPOSITORY + "/issues/5");
            assertThat(run.path("finishedAt").isNull()).isFalse();

            var sessionId = run.path("sessionId").asString();
            var session = get(setup, "/api/v1/sessions/" + sessionId);
            assertThat(session.path("title").asString()).isEqualTo("#5 Fix the parser crash!");
            assertThat(session.path("autoApprove").asBoolean()).isTrue();
            assertThat(session.path("mode").asString()).isEqualTo("agent");
            var workspace = get(setup, "/api/v1/sessions/" + sessionId + "/remote");
            assertThat(workspace.path("branch").asString()).isEqualTo("pointer/issue-5-fix-the-parser-crash");
            assertThat(workspace.path("pullRequestUrl").asString()).isEqualTo(PULL_REQUEST_URL);
        }

        assertThat(llm.requests().getFirst())
                .contains("Issue #5: Fix the parser crash!")
                .contains("It crashes on empty input")
                .contains("Also happens on Linux");
        assertThat(operations()).containsSubsequence("pointer.repo_prepare", "pointer.repo_publish", "run_command");
        var pullRequest = ArgumentCaptor.forClass(GitHubRepositories.PullRequest.class);
        verify(gitHub).openPullRequest(eq(GITHUB_TOKEN), pullRequest.capture());
        assertThat(pullRequest.getValue().draft()).isFalse();
        assertThat(pullRequest.getValue().title()).isEqualTo("Fix the parser crash!");
        assertThat(pullRequest.getValue().head()).isEqualTo("pointer/issue-5-fix-the-parser-crash");
        assertThat(pullRequest.getValue().body())
                .contains("Fixed the null check in the parser.")
                .contains("`make check`")
                .endsWith("Closes #5");
        verify(gitHub).createIssueComment(eq(GITHUB_TOKEN), eq(REPOSITORY), eq(ISSUE), contains("Pointer automation"));
        verify(gitHub, atLeastOnce())
                .updateIssueComment(eq(GITHUB_TOKEN), eq(REPOSITORY), eq(STATUS_COMMENT), contains(PULL_REQUEST_URL));
    }

    @Test
    void failedCheckIsSentBackToTheAgentAndTheSecondCheckPasses() throws Exception {
        var setup = automationSetUp(Map.of());
        checkResults.add(checkResult(1, "FAILED test_parser\nboom"));
        checkResults.add(checkResult(0, "ok"));
        llm.enqueueText("First try.");
        llm.enqueueText("Fixed the test.");
        startRun(setup).expectStatus().isCreated();

        try (var runner = startRunner(setup)) {
            var run = awaitRun(
                    setup, current -> "succeeded".equals(current.path("status").asString()));
            assertThat(run.path("attempts").asInt()).isEqualTo(2);
        }

        assertThat(llm.requests()).hasSize(2);
        assertThat(llm.requests().getLast())
                .contains("The check command `make check` failed with exit code 1")
                .contains("FAILED test_parser")
                .contains("Fix the cause.");
        var pullRequest = ArgumentCaptor.forClass(GitHubRepositories.PullRequest.class);
        verify(gitHub).openPullRequest(eq(GITHUB_TOKEN), pullRequest.capture());
        assertThat(pullRequest.getValue().body()).contains("Fixed the test.").contains("| 2 of 3 |");
    }

    @Test
    void exhaustedAttemptsOpenADraftPullRequestWithTheFailingOutput() throws Exception {
        var setup = automationSetUp(Map.of("maxAttempts", 2));
        checkResults.add(checkResult(1, "first failure"));
        checkResults.add(checkResult(2, "second failure"));
        llm.enqueueText("First try.");
        llm.enqueueText("Second try.");
        startRun(setup).expectStatus().isCreated();

        try (var runner = startRunner(setup)) {
            var run = awaitRun(
                    setup, current -> "failed".equals(current.path("status").asString()));
            assertThat(run.path("failureReason").asString()).isEqualTo("check_failed");
            assertThat(run.path("attempts").asInt()).isEqualTo(2);
            assertThat(run.path("lastCheckOutput").asString()).isEqualTo("second failure");
            assertThat(run.path("pullRequestUrl").asString()).isEqualTo(PULL_REQUEST_URL);
        }

        var pullRequest = ArgumentCaptor.forClass(GitHubRepositories.PullRequest.class);
        verify(gitHub).openPullRequest(eq(GITHUB_TOKEN), pullRequest.capture());
        assertThat(pullRequest.getValue().draft()).isTrue();
        assertThat(pullRequest.getValue().body())
                .contains("second failure")
                .contains("still fails after 2 checks")
                .endsWith("Refs #5");
    }

    @Test
    void turnWithoutChangesFailsTheRunAndPostsTheQuestionOnTheIssue() throws Exception {
        var setup = automationSetUp(Map.of());
        pushes = false;
        llm.enqueueText("Which parser do you mean?");
        startRun(setup).expectStatus().isCreated();

        try (var runner = startRunner(setup)) {
            var run = awaitRun(
                    setup, current -> "failed".equals(current.path("status").asString()));
            assertThat(run.path("failureReason").asString()).isEqualTo("needs_input");
            assertThat(run.path("pullRequestUrl").isNull()).isTrue();
        }

        verify(gitHub, never()).openPullRequest(anyString(), any());
        awaitTrue(() -> {
            try {
                verify(gitHub, atLeastOnce())
                        .updateIssueComment(
                                eq(GITHUB_TOKEN),
                                eq(REPOSITORY),
                                eq(STATUS_COMMENT),
                                contains("> Which parser do you mean?"));
                return true;
            } catch (AssertionError error) {
                return false;
            }
        });
        assertThat(operations()).doesNotContain("run_command");
    }

    @Test
    void budgetStopsTheRunAndKeepsTheWorkInADraft() throws Exception {
        var setup = automationSetUp(Map.of("budgetUsd", 0.01));
        priceModel(setup);
        checkResults.add(checkResult(1, "still failing"));
        llm.enqueueText("First try.");
        startRun(setup).expectStatus().isCreated();

        try (var runner = startRunner(setup)) {
            var run = awaitRun(
                    setup, current -> "failed".equals(current.path("status").asString()));
            assertThat(run.path("failureReason").asString()).isEqualTo("budget_exceeded");
            assertThat(run.path("costUsd").asDouble()).isEqualTo(0.017);
            assertThat(run.path("pullRequestUrl").asString()).isEqualTo(PULL_REQUEST_URL);
        }

        assertThat(llm.requests()).hasSize(1);
        var pullRequest = ArgumentCaptor.forClass(GitHubRepositories.PullRequest.class);
        verify(gitHub).openPullRequest(eq(GITHUB_TOKEN), pullRequest.capture());
        assertThat(pullRequest.getValue().draft()).isTrue();
    }

    @Test
    void cancellingARunStopsTheAgentTurn() throws Exception {
        var setup = automationSetUp(Map.of());
        llm.enqueueSilence(Duration.ofSeconds(30));
        startRun(setup).expectStatus().isCreated();

        try (var runner = startRunner(setup)) {
            var running = awaitRun(
                    setup, current -> "running".equals(current.path("status").asString()));
            awaitTrue(() -> !llm.requests().isEmpty());
            var runId = running.path("id").asString();
            client.post()
                    .uri("/api/v1/projects/{id}/automation/runs/{runId}/cancel", setup.projectId(), runId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo("cancelled");
            var sessionPath = "/api/v1/sessions/" + running.path("sessionId").asString();
            awaitTrue(() -> "idle".equals(get(setup, sessionPath).path("status").asString()));
            client.post()
                    .uri("/api/v1/projects/{id}/automation/runs/{runId}/cancel", setup.projectId(), runId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(409);
        }

        assertThat(awaitRun(setup, current -> true).path("status").asString()).isEqualTo("cancelled");
        verify(gitHub, never()).openPullRequest(anyString(), any());
    }

    @Test
    void labelWebhookStartsARunOnceAndRejectsBadSignatures() throws Exception {
        var setup = automationSetUp(Map.of("triggerLabel", "ai-fix"));
        var hook = ArgumentCaptor.forClass(GitHubRepositories.Hook.class);
        verify(gitHub).createHook(eq(GITHUB_TOKEN), eq(REPOSITORY), hook.capture());
        var url = hook.getValue().url().substring("https://pointer.example".length());
        var secret = hook.getValue().secret();
        var labeled = issueEvent("labeled", "AI-Fix");

        webhook(url, "issues", labeled, "sha256=" + "0".repeat(64))
                .expectStatus()
                .isUnauthorized();
        webhook(url, "issues", labeled, null).expectStatus().isUnauthorized();
        webhook(url, "ping", "{\"zen\":\"Keep it simple\"}", secret)
                .expectStatus()
                .isNoContent();
        webhook(url, "issues", issueEvent("labeled", "bug"), secret)
                .expectStatus()
                .isNoContent();
        webhook(url, "issues", issueEvent("opened", "ai-fix"), secret)
                .expectStatus()
                .isNoContent();
        webhook("/api/v1/webhooks/github/" + UUID.randomUUID(), "issues", labeled, secret)
                .expectStatus()
                .isNotFound();
        assertThat(runs(setup)).isEmpty();

        llm.enqueueText("Done.");
        webhook(url, "issues", labeled, secret)
                .expectStatus()
                .isAccepted()
                .expectBody()
                .jsonPath("$.runId")
                .exists();
        webhook(url, "issues", labeled, secret).expectStatus().isNoContent();

        try (var runner = startRunner(setup)) {
            var run = awaitRun(
                    setup, current -> "succeeded".equals(current.path("status").asString()));
            assertThat(run.path("trigger").asString()).isEqualTo("label");
        }
        assertThat(runs(setup)).hasSize(1);
    }

    @Test
    void telegramIssueCommandStartsARunAndReportsTheResult() throws Exception {
        var setup = automationSetUp(Map.of());
        var chatId = link(setup);
        checkResults.add(checkResult(1, "boom"));
        llm.enqueueText("First try.");
        llm.enqueueText("Fixed.");

        TELEGRAM.enqueueText(chatId, "/issue");
        TELEGRAM.awaitMessage(chatId, "/issue 42", TIMEOUT);
        TELEGRAM.enqueueText(chatId, "/issue #5");
        TELEGRAM.awaitMessage(chatId, "Run for issue #5", TIMEOUT);

        try (var runner = startRunner(setup)) {
            TELEGRAM.awaitMessage(chatId, "started working on the issue", TIMEOUT);
            TELEGRAM.awaitMessage(chatId, "Check 1 of 3 failed", TIMEOUT);
            var done = TELEGRAM.awaitMessage(chatId, "The pull request is ready", TIMEOUT);
            assertThat(done.body().path("text").asString())
                    .contains("#5 Fix the parser crash!")
                    .contains(PULL_REQUEST_URL);
            assertThat(awaitRun(
                                    setup,
                                    current -> "succeeded"
                                            .equals(current.path("status").asString()))
                            .path("trigger")
                            .asString())
                    .isEqualTo("telegram");
        }
    }

    @Test
    void stalledAndInterruptedRunsAreResumed() throws Exception {
        var setup = automationSetUp(Map.of());
        llm.enqueueText("Implemented.");
        startRun(setup).expectStatus().isCreated();

        try (var runner = startRunner(setup)) {
            var run = awaitRun(
                    setup, current -> "succeeded".equals(current.path("status").asString()));
            var runId = UUID.fromString(run.path("id").asString());

            llm.enqueueText("Continued.");
            reopen(runId, "running");
            awaitTrue(() -> llm.requests().size() == 2);
            assertThat(llm.requests().getLast()).contains("The server restarted while you were working");
            awaitRun(
                    setup,
                    current -> current.path("attempts").asInt() == 2
                            && "succeeded".equals(current.path("status").asString()));

            reopen(runId, "checking");
            ReflectionTestUtils.invokeMethod(context.getBean("automationRunner"), "recoverRuns");
            awaitRun(
                    setup,
                    current -> current.path("attempts").asInt() == 3
                            && "succeeded".equals(current.path("status").asString()));
            assertThat(llm.requests()).hasSize(2);
        }
    }

    @Test
    void automationNeedsARepositoryAndCanBeDisabledAndDeleted() {
        var setup = setUp(false);
        saveAutomation(setup, Map.of("enabled", true))
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("project_repository_missing");
        assertThat(automation(setup).path("configured").asBoolean()).isFalse();
        startRun(setup)
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("automation_not_configured");

        var configured = automationSetUp(Map.of());
        saveAutomation(configured, Map.of("enabled", false, "checkCommand", CHECK_COMMAND))
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.webhook")
                .isEqualTo("missing");
        verify(gitHub).deleteHook(GITHUB_TOKEN, REPOSITORY, WEBHOOK);
        startRun(configured)
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("automation_disabled");

        client.delete()
                .uri("/api/v1/projects/{id}/automation", configured.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(configured.auth()))
                .exchange()
                .expectStatus()
                .isNoContent();
        assertThat(automation(configured).path("configured").asBoolean()).isFalse();
    }

    private Setup automationSetUp(Map<String, Object> overrides) {
        var setup = setUp(false);
        client.patch()
                .uri("/api/v1/projects/{id}", setup.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("repository", REPOSITORY))
                .exchange()
                .expectStatus()
                .isOk();
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
        when(gitHub.issue(GITHUB_TOKEN, REPOSITORY, ISSUE))
                .thenReturn(new GitHubRepositories.Issue(
                        ISSUE,
                        "Fix the parser crash!",
                        "It crashes on empty input",
                        "octo",
                        "https://github.com/" + REPOSITORY + "/issues/5",
                        false));
        when(gitHub.issueComments(eq(GITHUB_TOKEN), eq(REPOSITORY), eq(ISSUE), anyInt()))
                .thenReturn(List.of(new GitHubRepositories.IssueComment("alice", "Also happens on Linux")));
        when(gitHub.createIssueComment(eq(GITHUB_TOKEN), eq(REPOSITORY), eq(ISSUE), anyString()))
                .thenReturn(STATUS_COMMENT);
        when(gitHub.createHook(eq(GITHUB_TOKEN), eq(REPOSITORY), any())).thenReturn(WEBHOOK);
        when(gitHub.openPullRequest(eq(GITHUB_TOKEN), any())).thenReturn(PULL_REQUEST_URL);
        var body = new HashMap<String, Object>();
        body.put("enabled", true);
        body.put("checkCommand", CHECK_COMMAND);
        body.put(
                "modelId",
                get(setup, "/api/v1/sessions/" + setup.sessionId())
                        .path("modelId")
                        .asString());
        body.putAll(overrides);
        saveAutomation(setup, body).expectStatus().isOk();
        return setup;
    }

    private RestTestClient.ResponseSpec saveAutomation(Setup setup, Map<String, Object> body) {
        return client.put()
                .uri("/api/v1/projects/{id}/automation", setup.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(body)
                .exchange();
    }

    private JsonNode automation(Setup setup) {
        return get(setup, "/api/v1/projects/" + setup.projectId() + "/automation");
    }

    private RestTestClient.ResponseSpec startRun(Setup setup) {
        return client.post()
                .uri("/api/v1/projects/{id}/automation/runs", setup.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("issueNumber", ISSUE))
                .exchange();
    }

    private JsonNode runs(Setup setup) {
        return get(setup, "/api/v1/projects/" + setup.projectId() + "/automation/runs");
    }

    private JsonNode awaitRun(Setup setup, Predicate<JsonNode> condition) {
        var deadline = Instant.now().plus(TIMEOUT.multipliedBy(2));
        JsonNode run = null;
        while (Instant.now().isBefore(deadline)) {
            run = runs(setup).path(0);
            if (!run.isMissingNode() && condition.test(run)) {
                return run;
            }
            sleep();
        }
        throw new AssertionError("The run did not reach the expected state: " + run);
    }

    private void reopen(UUID runId, String status) {
        jdbc.sql("update automation_runs set status = :status, finished_at = null where id = :id")
                .param("status", status)
                .param("id", runId)
                .update();
    }

    private RestTestClient.ResponseSpec webhook(String url, String event, String body, String secret) {
        var request = client.post().uri(url).header("X-GitHub-Event", event).contentType(MediaType.APPLICATION_JSON);
        if (secret != null) {
            request = request.header("X-Hub-Signature-256", secret.startsWith("sha256=") ? secret : sign(body, secret));
        }
        return request.body(body).exchange();
    }

    private static String issueEvent(String action, String label) {
        return "{\"action\":\"" + action + "\",\"label\":{\"name\":\"" + label + "\"},"
                + "\"issue\":{\"number\":5,\"title\":\"Fix the parser crash!\"}}";
    }

    private static String sign(String body, String secret) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void priceModel(Setup setup) {
        var provider = client.get()
                .uri("/api/v1/providers")
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectBody(ProviderDtos.ProviderResponse[].class)
                .returnResult()
                .getResponseBody()[0];
        client.patch()
                .uri(
                        "/api/v1/providers/{providerId}/models/{modelId}",
                        provider.id(),
                        provider.models().getFirst().id())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("inputPricePerMtok", 1000, "outputPricePerMtok", 1000))
                .exchange()
                .expectStatus()
                .isOk();
    }

    private long link(Setup setup) {
        var chatId = ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE / 2);
        var deadline = Instant.now().plus(TIMEOUT);
        while (true) {
            var response = client.post()
                    .uri("/api/v1/telegram/link")
                    .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                    .exchange()
                    .returnResult(JsonNode.class);
            if (response.getStatus().is2xxSuccessful()) {
                var url = response.getResponseBody().path("url").asString();
                TELEGRAM.enqueueText(chatId, "/start " + url.substring(url.indexOf("start=") + "start=".length()));
                TELEGRAM.awaitMessage(chatId, "Linked", TIMEOUT);
                return chatId;
            }
            assertThat(Instant.now()).isBefore(deadline);
            sleep();
        }
    }

    private GatewayTestClient startRunner(Setup setup) throws Exception {
        var runtime = (FakeContainerRuntime) containers;
        var spec = runtime.awaitCreated(TIMEOUT);
        awaitTrue(() -> runtime.isRunning(spec.name()));
        var deviceId = UUID.fromString(spec.environment().get("POINTER_DEVICE_ID"));
        var runner =
                GatewayTestClient.connect(port, runnerToken(spec), deviceId, List.of("executor", "viewer"), List.of());
        runner.onMessage(message -> {
            if (!type(message).equals("exec.request")) {
                return;
            }
            var request = message.path("payload");
            runnerRequests.add(request);
            runner.send(
                    "exec.result",
                    Map.of(
                            "callId", request.path("callId").asString(),
                            "status", "succeeded",
                            "result", result(request)));
        });
        runner.send(
                "device.projects_changed",
                Map.of("projects", List.of(setup.projectId().toString())));
        return runner;
    }

    private Map<String, Object> result(JsonNode request) {
        return switch (request.path("operation").asString()) {
            case "pointer.repo_prepare" -> Map.of("branch", "pointer/x", "baseBranch", "main", "head", "000");
            case "pointer.repo_publish" ->
                pushes
                        ? Map.of("committed", true, "pushed", true, "commit", "abc1234")
                        : Map.of("committed", false, "pushed", false, "commit", "000");
            case "run_command" -> {
                var queued = CHECK_COMMAND.equals(
                                request.path("arguments").path("command").asString())
                        ? checkResults.poll()
                        : null;
                yield queued == null ? checkResult(0, "ok") : queued;
            }
            default -> Map.of();
        };
    }

    private static Map<String, Object> checkResult(int exitCode, String output) {
        return Map.of("exitCode", exitCode, "output", output, "truncated", false);
    }

    private List<String> operations() {
        return runnerRequests.stream()
                .map(request -> request.path("operation").asString())
                .filter(operation -> !operation.equals("pointer.project_context"))
                .toList();
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

    private JsonNode get(Setup setup, String uri) {
        return client.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    private static void awaitTrue(BooleanSupplier condition) {
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
