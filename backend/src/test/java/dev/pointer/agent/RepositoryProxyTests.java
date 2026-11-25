package dev.pointer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import dev.pointer.container.GitHubRepositories;
import dev.pointer.testing.FakeContainerRuntime;
import dev.pointer.testing.FakeGitServer;
import dev.pointer.user.UserIdentity;
import dev.pointer.user.UserService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

@TestPropertySource(
        properties = {
            "pointer.container.enabled=true",
            "pointer.container.idle-check-interval=1h",
            "pointer.container.max-running=8"
        })
class RepositoryProxyTests extends AgentIntegrationTest {

    private static final String GITHUB_TOKEN = "gho_proxy_token";
    private static final String ZERO_ID = "0".repeat(40);
    private static final FakeGitServer GIT = startGitServer();

    @TestConfiguration
    static class Runtime {

        @Bean
        @Primary
        FakeContainerRuntime fakeContainerRuntime() {
            return new FakeContainerRuntime();
        }
    }

    @DynamicPropertySource
    static void gitServer(DynamicPropertyRegistry registry) {
        registry.add("pointer.container.github-git-url", GIT::baseUrl);
    }

    @AfterAll
    static void stopGitServer() {
        GIT.close();
    }

    @Autowired
    private FakeContainerRuntime containers;

    @Autowired
    private UserService users;

    @MockitoBean
    private GitHubRepositories gitHub;

    @Test
    void cloneFetchAndPushToTheSessionBranchPassThroughWithTheStoredToken() throws Exception {
        var repository = uniqueRepository();
        var bare = GIT.createRepository(repository);
        var session = remoteSession(repository);
        var clone = clone(session, repository);

        assertThat(clone.resolve("README.md")).hasContent("# Demo\n");
        var expected = "Basic "
                + Base64.getEncoder()
                        .encodeToString(("x-access-token:" + GITHUB_TOKEN).getBytes(StandardCharsets.UTF_8));
        assertThat(GIT.requests())
                .filteredOn(request -> request.path().startsWith("/" + repository))
                .isNotEmpty()
                .allSatisfy(request -> assertThat(request.authorization()).isEqualTo(expected));

        commitFile(clone, "change.txt", "content\n");
        var pushed = git(clone, session.token(), "push", "origin", "HEAD:refs/heads/" + session.branch());
        assertThat(pushed.exitCode()).as(pushed.output()).isZero();
        assertThat(FakeGitServer.git(bare, "log", "-1", "--format=%s", session.branch()))
                .contains("Add change.txt");

        var fetched = git(clone, session.token(), "fetch", "origin");
        assertThat(fetched.exitCode()).as(fetched.output()).isZero();
    }

    @Test
    void pushesOutsideTheSessionBranchAreRefusedBeforeReachingGitHub() throws Exception {
        var repository = uniqueRepository();
        var bare = GIT.createRepository(repository);
        var session = remoteSession(repository);
        var clone = clone(session, repository);
        var mainBefore = FakeGitServer.git(bare, "rev-parse", "main").strip();
        commitFile(clone, "change.txt", "content\n");
        assertThat(git(clone, session.token(), "push", "origin", "HEAD:refs/heads/" + session.branch())
                        .exitCode())
                .isZero();
        GIT.clearRequests();

        var toMain = git(clone, session.token(), "push", "origin", "HEAD:refs/heads/main");
        assertThat(toMain.exitCode()).isNotZero();
        assertThat(toMain.output()).contains("Pointer allows pushes to " + session.branch() + " only");

        FakeGitServer.git(clone, "tag", "v1");
        var tag = git(clone, session.token(), "push", "origin", "refs/tags/v1");
        assertThat(tag.exitCode()).isNotZero();
        assertThat(tag.output()).contains("Pointer allows pushes to");

        var deletion = git(clone, session.token(), "push", "origin", ":refs/heads/" + session.branch());
        assertThat(deletion.exitCode()).isNotZero();
        assertThat(deletion.output()).contains("does not allow deleting " + session.branch());

        assertThat(GIT.requests())
                .noneMatch(request ->
                        request.method().equals("POST") && request.path().endsWith("/git-receive-pack"));
        assertThat(FakeGitServer.git(bare, "rev-parse", "main").strip()).isEqualTo(mainBefore);
        assertThat(FakeGitServer.git(bare, "tag", "--list")).isBlank();
        assertThat(FakeGitServer.git(bare, "branch", "--list", session.branch()))
                .contains(session.branch());
    }

    @Test
    void gzipEncodedCommandListsAreCheckedAndRefusalsWithoutReportStatusAreForbidden() throws Exception {
        var repository = uniqueRepository();
        GIT.createRepository(repository);
        var session = remoteSession(repository);
        var newId = "1".repeat(40);
        GIT.clearRequests();

        var reported = postReceivePack(
                session.token(),
                repository,
                gzip(packet(ZERO_ID + " " + newId + " refs/heads/main\0report-status side-band-64k\n")),
                true);
        assertThat(reported.statusCode()).isEqualTo(200);
        assertThat(reported.body()).contains("ng refs/heads/main Pointer allows pushes to");

        var refused = postReceivePack(
                session.token(), repository, packet(ZERO_ID + " " + newId + " refs/heads/main\0 agent=test\n"), false);
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(refused.body()).contains("Pointer allows pushes to");

        assertThat(GIT.requests()).isEmpty();
    }

    @Test
    void otherRunnersUsersAndRepositoriesAreNotFound() throws Exception {
        var repository = uniqueRepository();
        var other = uniqueRepository();
        GIT.createRepository(repository);
        GIT.createRepository(other);
        var session = remoteSession(repository);
        var otherSession = remoteSession(other);

        assertThat(advertise(otherSession.token(), repository)).isEqualTo(404);
        assertThat(advertise(session.token(), other)).isEqualTo(404);
        assertThat(advertise(session.userToken(), repository)).isEqualTo(404);
        assertThat(advertise(null, repository)).isEqualTo(401);
        assertThat(advertise(session.token(), repository)).isEqualTo(200);
    }

    @Test
    void largePacksAreStreamedThroughTheProxy() throws Exception {
        var repository = uniqueRepository();
        var bare = GIT.createRepository(repository);
        var session = remoteSession(repository);
        var clone = clone(session, repository);
        var data = new byte[8 * 1024 * 1024];
        new Random(7).nextBytes(data);
        Files.write(clone.resolve("large.bin"), data);
        FakeGitServer.git(clone, "add", ".");
        FakeGitServer.git(clone, "commit", "--quiet", "-m", "Add large file");

        var pushed = git(clone, session.token(), "push", "origin", "HEAD:refs/heads/" + session.branch());

        assertThat(pushed.exitCode()).as(pushed.output()).isZero();
        assertThat(FakeGitServer.git(bare, "cat-file", "-s", session.branch() + ":large.bin")
                        .strip())
                .isEqualTo(Integer.toString(data.length));
    }

    private record Session(String token, String userToken, String branch) {}

    private record GitOutcome(int exitCode, String output) {}

    private Session remoteSession(String repository) throws Exception {
        var setup = setUp(false);
        var userId = setup.auth().user().id();
        client.patch()
                .uri("/api/v1/projects/{id}", setup.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of("repository", repository))
                .exchange()
                .expectStatus()
                .isOk();
        var identity = users.linkIdentity(
                userId,
                UserIdentity.GITHUB,
                Long.toString(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)),
                "octo");
        users.storeIdentityToken(identity, "octo", GITHUB_TOKEN, "read:user,user:email,repo");
        when(gitHub.repository(GITHUB_TOKEN, repository))
                .thenReturn(new GitHubRepositories.Repository(
                        repository, "main", "https://github.com/" + repository + ".git", true));
        var created = client.post()
                .uri("/api/v1/projects/{id}/remote-sessions", setup.projectId())
                .header(HttpHeaders.AUTHORIZATION, bearer(setup.auth()))
                .body(Map.of())
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
        var sessionId = UUID.fromString(created.path("session").path("id").asString());
        var spec = containers.awaitCreatedNamed("pointer-session-" + sessionId, TIMEOUT);
        assertThat(spec.environment()).doesNotContainValue(GITHUB_TOKEN);
        var token = client.post()
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
        return new Session(
                token,
                setup.auth().accessToken(),
                created.path("workspace").path("branch").asString());
    }

    private Path clone(Session session, String repository) throws Exception {
        var parent = Files.createTempDirectory("pointer-clone");
        var cloned = git(parent, session.token(), "clone", "--quiet", proxyUrl(repository), "repo");
        assertThat(cloned.exitCode()).as(cloned.output()).isZero();
        var clone = parent.resolve("repo");
        FakeGitServer.git(clone, "config", "user.name", "Test");
        FakeGitServer.git(clone, "config", "user.email", "test@example.test");
        return clone;
    }

    private static void commitFile(Path clone, String name, String content) throws Exception {
        Files.writeString(clone.resolve(name), content);
        FakeGitServer.git(clone, "add", ".");
        FakeGitServer.git(clone, "commit", "--quiet", "-m", "Add " + name);
    }

    private static GitOutcome git(Path directory, String token, String... args)
            throws IOException, InterruptedException {
        var command = new ArrayList<String>(
                List.of("git", "-c", "http.extraHeader=Authorization: Bearer " + token, "-c", "credential.helper="));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new GitOutcome(process.waitFor(), output);
    }

    private int advertise(String token, String repository) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(proxyUrl(repository) + "/info/refs?service=git-upload-pack"))
                .GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        try (var http = HttpClient.newHttpClient()) {
            return http.send(request.build(), HttpResponse.BodyHandlers.discarding())
                    .statusCode();
        }
    }

    private HttpResponse<String> postReceivePack(String token, String repository, byte[] body, boolean gzip)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create(proxyUrl(repository) + "/git-receive-pack"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/x-git-receive-pack-request")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (gzip) {
            request.header("Content-Encoding", "gzip");
        }
        try (var http = HttpClient.newHttpClient()) {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.ISO_8859_1));
        }
    }

    private String proxyUrl(String repository) {
        return "http://localhost:" + port + "/api/v1/runner/git/" + repository + ".git";
    }

    private static byte[] packet(String line) {
        var payload = line.getBytes(StandardCharsets.UTF_8);
        var output = new ByteArrayOutputStream();
        output.writeBytes(String.format("%04x", payload.length + 4).getBytes(StandardCharsets.US_ASCII));
        output.writeBytes(payload);
        output.writeBytes("0000".getBytes(StandardCharsets.US_ASCII));
        return output.toByteArray();
    }

    private static byte[] gzip(byte[] data) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var compressed = new GZIPOutputStream(output)) {
            compressed.write(data);
        }
        return output.toByteArray();
    }

    private static String uniqueRepository() {
        return "octo/demo-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static FakeGitServer startGitServer() {
        try {
            return new FakeGitServer();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
