package dev.pointer.container;

import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

class DockerContainerRuntime implements ContainerRuntime {

    private static final String WORKSPACE = "/workspace";
    private static final int OK = 200;
    private static final int NOT_MODIFIED = 304;
    private static final int NOT_FOUND = 404;
    private static final int CONFLICT = 409;

    private final ContainerProperties properties;
    private final DockerHttpClient client;
    private final JsonMapper json;

    DockerContainerRuntime(ContainerProperties properties, JsonMapper json) {
        this.properties = properties;
        this.json = json;
        this.client = new ZerodepDockerHttpClient.Builder()
                .dockerHost(URI.create(dockerHost(properties)))
                .connectionTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofMinutes(2))
                .build();
    }

    @Override
    public boolean isAvailable() {
        try {
            return call(DockerHttpClient.Request.Method.GET, "/_ping", null).status() == OK;
        } catch (ContainerException exception) {
            return false;
        }
    }

    @Override
    public boolean exists(String name) {
        var inspect = call(DockerHttpClient.Request.Method.GET, "/containers/" + encode(name) + "/json", null);
        if (inspect.status() == NOT_FOUND) {
            return false;
        }
        inspect.ensureSuccess("inspect the container");
        return true;
    }

    @Override
    public void create(ContainerSpec spec) {
        createVolume(spec);
        createContainer(spec);
    }

    @Override
    public void start(String name) {
        var started = call(DockerHttpClient.Request.Method.POST, "/containers/" + encode(name) + "/start", null);
        if (started.status() != NOT_MODIFIED) {
            started.ensureSuccess("start the container");
        }
    }

    @Override
    public void stop(String name) {
        var stopped = call(DockerHttpClient.Request.Method.POST, "/containers/" + encode(name) + "/stop?t=10", null);
        if (stopped.status() != NOT_MODIFIED && stopped.status() != NOT_FOUND) {
            stopped.ensureSuccess("stop the container");
        }
    }

    @Override
    public void remove(String name, String volume) {
        var removed = call(DockerHttpClient.Request.Method.DELETE, "/containers/" + encode(name) + "?force=true", null);
        if (removed.status() != NOT_FOUND) {
            removed.ensureSuccess("remove the container");
        }
        var volumeRemoved =
                call(DockerHttpClient.Request.Method.DELETE, "/volumes/" + encode(volume) + "?force=true", null);
        if (volumeRemoved.status() != NOT_FOUND) {
            volumeRemoved.ensureSuccess("remove the volume");
        }
    }

    private void createVolume(ContainerSpec spec) {
        var created = call(
                DockerHttpClient.Request.Method.POST,
                "/volumes/create",
                Map.of("Name", spec.volume(), "Labels", spec.labels()));
        if (created.status() != CONFLICT) {
            created.ensureSuccess("create the volume");
        }
    }

    private void createContainer(ContainerSpec spec) {
        var environment = new ArrayList<String>();
        spec.environment().forEach((key, value) -> environment.add(key + "=" + value));
        var hostConfig = new LinkedHashMap<String, Object>();
        hostConfig.put("Binds", List.of(spec.volume() + ":" + WORKSPACE));
        hostConfig.put("NanoCpus", Math.round(properties.cpus() * 1_000_000_000L));
        hostConfig.put("Memory", properties.memory().toBytes());
        hostConfig.put("PidsLimit", properties.pidsLimit());
        hostConfig.put("CapDrop", List.of("ALL"));
        hostConfig.put("SecurityOpt", List.of("no-new-privileges"));
        hostConfig.put("ExtraHosts", List.of("host.docker.internal:host-gateway"));
        if (properties.network() != null && !properties.network().isBlank()) {
            hostConfig.put("NetworkMode", properties.network());
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("Image", properties.image());
        body.put("Env", environment);
        body.put("Labels", spec.labels());
        body.put("WorkingDir", WORKSPACE);
        body.put("HostConfig", hostConfig);
        var created =
                call(DockerHttpClient.Request.Method.POST, "/containers/create?name=" + encode(spec.name()), body);
        if (created.status() == NOT_FOUND) {
            throw new ContainerException("The runner image " + properties.image() + " is not available");
        }
        created.ensureSuccess("create the container");
    }

    private Reply call(DockerHttpClient.Request.Method method, String path, Object body) {
        var request = DockerHttpClient.Request.builder().method(method).path(path);
        if (body != null) {
            request.putHeader("Content-Type", "application/json")
                    .body(new ByteArrayInputStream(json.writeValueAsBytes(body)));
        }
        try (var response = client.execute(request.build())) {
            return new Reply(response.getStatusCode(), read(response.getBody()));
        } catch (IOException | RuntimeException exception) {
            throw new ContainerException("Docker is not reachable: " + exception.getMessage());
        }
    }

    private static String read(InputStream body) throws IOException {
        return body == null ? "" : new String(body.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String dockerHost(ContainerProperties properties) {
        if (properties.dockerHost() != null && !properties.dockerHost().isBlank()) {
            return properties.dockerHost();
        }
        var fromEnvironment = System.getenv("DOCKER_HOST");
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment;
        }
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "npipe:////./pipe/docker_engine"
                : "unix:///var/run/docker.sock";
    }

    private record Reply(int status, String body) {

        void ensureSuccess(String action) {
            if (status < OK || status >= 300) {
                throw new ContainerException("Docker could not " + action + " (" + status + "): " + body.strip());
            }
        }
    }
}
