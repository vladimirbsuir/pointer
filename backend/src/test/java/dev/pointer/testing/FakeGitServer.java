package dev.pointer.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

public final class FakeGitServer implements AutoCloseable {

    private final HttpServer server;
    private final Path root;
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    public record Request(String method, String path, String authorization) {}

    public FakeGitServer() throws IOException {
        root = Files.createTempDirectory("pointer-git");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public void clearRequests() {
        requests.clear();
    }

    public Path createRepository(String fullName) throws IOException, InterruptedException {
        var bare = root.resolve(fullName + ".git");
        Files.createDirectories(bare.getParent());
        git(root, "init", "--quiet", "--bare", "--initial-branch=main", bare.toString());
        git(bare, "config", "http.receivepack", "true");
        var seed = Files.createTempDirectory("pointer-seed");
        git(seed, "init", "--quiet", "--initial-branch=main");
        Files.writeString(seed.resolve("README.md"), "# Demo\n");
        git(seed, "add", ".");
        git(seed, "-c", "user.name=Test", "-c", "user.email=test@example.test", "commit", "--quiet", "-m", "init");
        git(seed, "push", "--quiet", bare.toString(), "main");
        return bare;
    }

    public static String git(Path directory, String... args) throws IOException, InterruptedException {
        var command = new ArrayList<String>();
        command.add("git");
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed: " + output);
        }
        return output;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var uri = exchange.getRequestURI();
            requests.add(new Request(
                    exchange.getRequestMethod(),
                    uri.getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization")));
            var body = exchange.getRequestBody().readAllBytes();
            var builder = new ProcessBuilder("git", "http-backend");
            var environment = builder.environment();
            environment.put("GIT_PROJECT_ROOT", root.toString());
            environment.put("GIT_HTTP_EXPORT_ALL", "1");
            environment.put("REMOTE_USER", "pointer");
            environment.put("REQUEST_METHOD", exchange.getRequestMethod());
            environment.put("PATH_INFO", uri.getPath());
            environment.put("QUERY_STRING", uri.getRawQuery() == null ? "" : uri.getRawQuery());
            environment.put("CONTENT_LENGTH", Integer.toString(body.length));
            putHeader(exchange, environment, "Content-Type", "CONTENT_TYPE");
            putHeader(exchange, environment, "Content-Encoding", "HTTP_CONTENT_ENCODING");
            putHeader(exchange, environment, "Git-Protocol", "GIT_PROTOCOL");
            var process = builder.start();
            Thread.ofVirtual().start(() -> {
                try (var input = process.getOutputStream()) {
                    input.write(body);
                } catch (IOException ignored) {
                    process.destroy();
                }
            });
            Thread.ofVirtual().start(() -> {
                try {
                    process.getErrorStream().transferTo(OutputStream.nullOutputStream());
                } catch (IOException ignored) {
                    process.destroy();
                }
            });
            var output = process.getInputStream().readAllBytes();
            respond(exchange, output);
        }
    }

    private static void respond(HttpExchange exchange, byte[] output) throws IOException {
        var separator = indexOf(output, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        var headerLength = separator < 0 ? 0 : separator;
        var headers = new String(output, 0, headerLength, StandardCharsets.UTF_8);
        var status = 200;
        for (var line : headers.split("\r\n")) {
            var colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            var name = line.substring(0, colon).strip();
            var value = line.substring(colon + 1).strip();
            if (name.equalsIgnoreCase("Status")) {
                status = Integer.parseInt(value.split(" ")[0]);
            } else {
                exchange.getResponseHeaders().add(name, value);
            }
        }
        var bodyStart = separator < 0 ? 0 : separator + 4;
        var length = output.length - bodyStart;
        exchange.sendResponseHeaders(status, length == 0 ? -1 : length);
        if (length > 0) {
            exchange.getResponseBody().write(output, bodyStart, length);
        }
    }

    private static void putHeader(
            HttpExchange exchange, Map<String, String> environment, String header, String variable) {
        var value = exchange.getRequestHeaders().getFirst(header);
        if (value != null) {
            environment.put(variable, value);
        }
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (var index = 0; index <= data.length - pattern.length; index++) {
            for (var offset = 0; offset < pattern.length; offset++) {
                if (data[index + offset] != pattern[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
