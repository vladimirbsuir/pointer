package dev.pointer.container;

import dev.pointer.common.error.ApiException;
import dev.pointer.device.DeviceService;
import dev.pointer.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RepositoryProxy {

    static final String UPLOAD_PACK = "git-upload-pack";
    static final String RECEIVE_PACK = "git-receive-pack";

    private static final Logger log = LoggerFactory.getLogger(RepositoryProxy.class);
    private static final List<String> FORWARDED_REQUEST_HEADERS =
            List.of("Content-Type", "Accept", "Git-Protocol", "User-Agent");
    private static final List<String> FORWARDED_RESPONSE_HEADERS =
            List.of("Content-Type", "Cache-Control", "Expires", "Pragma");
    private static final String GZIP = "gzip";
    private static final int FORBIDDEN = 403;
    private static final int OK = 200;

    private final ContainerProperties properties;
    private final RemoteWorkspaceRepository workspaces;
    private final DeviceService devices;
    private final UserService users;
    private final HttpClient http;

    RepositoryProxy(
            ContainerProperties properties,
            RemoteWorkspaceRepository workspaces,
            DeviceService devices,
            UserService users) {
        this.properties = properties;
        this.workspaces = workspaces;
        this.devices = devices;
        this.users = users;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    static String cloneUrl(String apiUrl, String repository) {
        return apiUrl.replaceAll("/+$", "") + "/api/v1/runner/git/" + repository + ".git";
    }

    void advertise(
            UUID deviceId,
            String owner,
            String name,
            String service,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        if (!UPLOAD_PACK.equals(service) && !RECEIVE_PACK.equals(service)) {
            throw ApiException.forbidden("git_service_unsupported", "Only fetch and push are supported");
        }
        var target = authorize(deviceId, owner, name);
        var upstream =
                upstream(target, "info/refs?service=" + service, request).GET().build();
        relay(upstream, response);
    }

    void uploadPack(UUID deviceId, String owner, String name, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        var target = authorize(deviceId, owner, name);
        var body = request.getInputStream();
        var builder = upstream(target, UPLOAD_PACK, request);
        var encoding = request.getHeader("Content-Encoding");
        if (encoding != null && !encoding.isBlank()) {
            builder.header("Content-Encoding", encoding);
        }
        var upstream =
                builder.POST(publisher(body, request.getContentLengthLong())).build();
        relay(upstream, response);
    }

    void receivePack(UUID deviceId, String owner, String name, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        var target = authorize(deviceId, owner, name);
        var gzip = GZIP.equals(contentEncoding(request));
        InputStream body = gzip ? new GZIPInputStream(request.getInputStream()) : request.getInputStream();
        ReceivePackCommands commands;
        try {
            commands = ReceivePackCommands.read(body);
        } catch (ReceivePackCommands.MalformedCommandsException exception) {
            reject(response, "The push could not be read: " + exception.getMessage());
            return;
        }
        var denial = denial(commands, target.workspace().getBranch());
        if (denial != null) {
            body.transferTo(OutputStream.nullOutputStream());
            if (ReceivePackRejection.canReport(commands)) {
                response.setStatus(OK);
                response.setContentType("application/x-git-receive-pack-result");
                response.setHeader("Cache-Control", "no-cache");
                response.getOutputStream().write(ReceivePackRejection.report(commands, denial));
                response.flushBuffer();
            } else {
                reject(response, denial);
            }
            return;
        }
        var forwarded = new SequenceInputStream(new ByteArrayInputStream(commands.consumed()), body);
        var length = gzip ? -1 : request.getContentLengthLong();
        var upstream = upstream(target, RECEIVE_PACK, request)
                .POST(publisher(forwarded, length))
                .build();
        relay(upstream, response);
    }

    static String denial(ReceivePackCommands commands, String branch) {
        if (commands.signed()) {
            return "Signed pushes are not supported by Pointer";
        }
        var allowed = "refs/heads/" + branch;
        for (var command : commands.commands()) {
            if (!allowed.equals(command.ref())) {
                return "Pointer allows pushes to " + branch + " only";
            }
            if (command.isDeletion()) {
                return "Pointer does not allow deleting " + branch;
            }
        }
        return null;
    }

    private Target authorize(UUID deviceId, String owner, String name) {
        var repository = owner + "/" + name;
        var workspace = workspaces
                .findByDeviceId(deviceId)
                .filter(candidate -> candidate.getRepository().equalsIgnoreCase(repository))
                .filter(candidate -> devices.isActiveRunner(deviceId))
                .orElseThrow(() -> ApiException.notFound("repository_not_found", "Repository not found"));
        var credentials = users.gitHubCredentials(workspace.getUserId())
                .orElseThrow(() -> ApiException.unauthorized(
                        "github_repository_access_required",
                        "Allow Pointer to access your GitHub repositories in settings"));
        return new Target(workspace, credentials.accessToken());
    }

    private HttpRequest.Builder upstream(Target target, String path, HttpServletRequest request) {
        var base = properties.githubGitUrl().replaceAll("/+$", "");
        var builder = HttpRequest.newBuilder(
                        URI.create(base + "/" + target.workspace().getRepository() + ".git/" + path))
                .header(
                        "Authorization",
                        "Basic "
                                + Base64.getEncoder()
                                        .encodeToString(
                                                ("x-access-token:" + target.token()).getBytes(StandardCharsets.UTF_8)));
        for (var name : FORWARDED_REQUEST_HEADERS) {
            var value = request.getHeader(name);
            if (value != null && !value.isBlank()) {
                builder.header(name, value);
            }
        }
        return builder;
    }

    private void relay(HttpRequest upstream, HttpServletResponse response) throws IOException {
        HttpResponse<InputStream> answer;
        try {
            answer = http.send(upstream, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiException.badGateway("github_unreachable", "GitHub is not reachable");
        } catch (IOException exception) {
            log.warn("Repository proxy could not reach {}: {}", upstream.uri().getHost(), exception.getMessage());
            throw ApiException.badGateway("github_unreachable", "GitHub is not reachable");
        }
        response.setStatus(answer.statusCode());
        for (var name : FORWARDED_RESPONSE_HEADERS) {
            answer.headers().firstValue(name).ifPresent(value -> response.setHeader(name, value));
        }
        try (var body = answer.body()) {
            body.transferTo(response.getOutputStream());
        }
        response.flushBuffer();
    }

    private static void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(FORBIDDEN);
        response.setContentType("text/plain; charset=utf-8");
        response.getOutputStream().write((message + "\n").getBytes(StandardCharsets.UTF_8));
        response.flushBuffer();
    }

    private static HttpRequest.BodyPublisher publisher(InputStream body, long length) {
        var stream = HttpRequest.BodyPublishers.ofInputStream(() -> body);
        return length >= 0 ? HttpRequest.BodyPublishers.fromPublisher(stream, length) : stream;
    }

    private static String contentEncoding(HttpServletRequest request) {
        var encoding = request.getHeader("Content-Encoding");
        return encoding == null || encoding.isBlank()
                ? "identity"
                : encoding.strip().toLowerCase(Locale.ROOT);
    }

    private record Target(RemoteWorkspace workspace, String token) {}
}
