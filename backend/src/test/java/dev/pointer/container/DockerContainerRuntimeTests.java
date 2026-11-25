package dev.pointer.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;

class DockerContainerRuntimeTests {

    private static final String IMAGE = "alpine:3.20";

    private static DockerClient docker;

    private final String name = "pointer-test-" + UUID.randomUUID();
    private final String volume = "pointer-test-volume-" + UUID.randomUUID();

    @BeforeAll
    static void pullImage() throws InterruptedException {
        docker = DockerClientFactory.instance().client();
        docker.pullImageCmd("alpine").withTag("3.20").start().awaitCompletion();
    }

    @AfterEach
    void removeContainer() {
        runtime(512).remove(name, volume);
    }

    @Test
    void createsARootContainerWithTheReducedCapabilitySetAndTheSpecLabel() {
        var runtime = runtime(512);
        var spec = spec(Map.of("POINTER_DEVICE_ID", "device", "POINTER_RUNNER_SECRET", "secret"));

        runtime.create(spec);

        var container = docker.inspectContainerCmd(name).exec();
        var host = container.getHostConfig();
        assertThat(container.getConfig().getUser()).isEqualTo("0");
        assertThat(host.getCapDrop()).extracting(Enum::name).containsExactly("ALL");
        assertThat(host.getCapAdd())
                .extracting(Enum::name)
                .containsExactlyInAnyOrderElementsOf(DockerContainerRuntime.CAPABILITIES);
        assertThat(host.getSecurityOpts()).containsExactly("no-new-privileges");
        assertThat(host.getShmSize()).isEqualTo(DataSize.ofMegabytes(64).toBytes());
        assertThat(host.getPidsLimit()).isEqualTo(512L);
        assertThat(host.getInit()).isTrue();
        assertThat(container.getConfig().getLabels())
                .containsEntry("dev.pointer.session", "test")
                .containsEntry(DockerContainerRuntime.SPEC_LABEL, runtime.specHash(spec));
    }

    @Test
    void aChangedSpecMarksTheContainerOutdatedAndItIsRemovedWithoutItsVolume() {
        var runtime = runtime(512);
        var spec = spec(Map.of("POINTER_DEVICE_ID", "device"));
        runtime.create(spec);

        assertThat(runtime.isOutdated(name, spec(Map.of("POINTER_DEVICE_ID", "other value"))))
                .isFalse();
        assertThat(runtime(1024).isOutdated(name, spec)).isTrue();
        assertThat(runtime.isOutdated(name, spec(Map.of("POINTER_DEVICE_ID", "device", "POINTER_NEW", "x"))))
                .isTrue();
        assertThat(runtime.isOutdated("pointer-test-missing-" + UUID.randomUUID(), spec))
                .isFalse();

        runtime.removeContainer(name);

        assertThat(runtime.exists(name)).isFalse();
        assertThat(docker.inspectVolumeCmd(volume).exec().getName()).isEqualTo(volume);
        runtime.create(spec);
        assertThat(runtime.exists(name)).isTrue();
    }

    @Test
    void removingTheSessionRemovesTheVolumeToo() {
        var runtime = runtime(512);
        runtime.create(spec(Map.of()));

        runtime.remove(name, volume);

        assertThat(runtime.exists(name)).isFalse();
        assertThatThrownBy(() -> docker.inspectVolumeCmd(volume).exec()).isInstanceOf(NotFoundException.class);
    }

    private ContainerSpec spec(Map<String, String> environment) {
        return new ContainerSpec(name, volume, environment, Map.of("dev.pointer.session", "test"));
    }

    private static DockerContainerRuntime runtime(long pidsLimit) {
        var properties = new ContainerProperties(
                true,
                null,
                IMAGE,
                "http://backend:8080",
                null,
                0.5,
                DataSize.ofMegabytes(256),
                pidsLimit,
                DataSize.ofMegabytes(64),
                1,
                Duration.ofMinutes(30),
                Duration.ofMinutes(10),
                "https://api.github.com",
                "https://github.com",
                false,
                ContainerProperties.DesktopAccess.NETWORK);
        return new DockerContainerRuntime(properties, JsonMapper.builder().build());
    }
}
