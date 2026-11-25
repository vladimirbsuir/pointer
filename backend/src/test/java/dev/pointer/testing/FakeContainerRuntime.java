package dev.pointer.testing;

import dev.pointer.container.ContainerRuntime;
import dev.pointer.container.ContainerSpec;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

public class FakeContainerRuntime implements ContainerRuntime {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(20);

    private final Map<String, ContainerSpec> containers = new ConcurrentHashMap<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final List<ContainerSpec> created = new CopyOnWriteArrayList<>();
    private final Set<ContainerSpec> claimed = ConcurrentHashMap.newKeySet();
    private final List<String> starts = new CopyOnWriteArrayList<>();
    private final List<String> removed = new CopyOnWriteArrayList<>();
    private final Set<String> outdated = ConcurrentHashMap.newKeySet();
    private final List<String> removedContainers = new CopyOnWriteArrayList<>();
    private volatile URI streamAddress;

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public boolean exists(String name) {
        return containers.containsKey(name);
    }

    @Override
    public void create(ContainerSpec spec) {
        containers.put(spec.name(), spec);
        created.add(spec);
    }

    @Override
    public void start(String name) {
        running.add(name);
        starts.add(name);
    }

    @Override
    public void stop(String name) {
        running.remove(name);
    }

    @Override
    public void remove(String name, String volume) {
        containers.remove(name);
        running.remove(name);
        removed.add(name);
    }

    @Override
    public boolean isOutdated(String name, ContainerSpec spec) {
        return outdated.contains(name);
    }

    @Override
    public void removeContainer(String name) {
        containers.remove(name);
        running.remove(name);
        outdated.remove(name);
        removedContainers.add(name);
    }

    @Override
    public Optional<URI> streamAddress(String name) {
        return running.contains(name) ? Optional.ofNullable(streamAddress) : Optional.empty();
    }

    public void useStreamAddress(URI address) {
        this.streamAddress = address;
    }

    public void markOutdated(String name) {
        outdated.add(name);
    }

    public List<String> removedContainers() {
        return List.copyOf(removedContainers);
    }

    public List<ContainerSpec> createdNamed(String name) {
        return created.stream().filter(spec -> spec.name().equals(name)).toList();
    }

    public ContainerSpec awaitCreatedForProject(Object projectId, Duration timeout) throws InterruptedException {
        return awaitCreated(
                spec -> String.valueOf(projectId).equals(spec.environment().get("POINTER_PROJECT_ID")), timeout);
    }

    public ContainerSpec awaitCreatedNamed(String name, Duration timeout) throws InterruptedException {
        return awaitCreated(spec -> spec.name().equals(name), timeout);
    }

    private ContainerSpec awaitCreated(Predicate<ContainerSpec> matching, Duration timeout)
            throws InterruptedException {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (var spec : created) {
                if (matching.test(spec) && claimed.add(spec)) {
                    return spec;
                }
            }
            Thread.sleep(POLL_INTERVAL);
        }
        throw new AssertionError("No matching container was created within " + timeout);
    }

    public boolean isRunning(String name) {
        return running.contains(name);
    }

    public List<String> starts() {
        return List.copyOf(starts);
    }

    public List<String> removed() {
        return List.copyOf(removed);
    }
}
