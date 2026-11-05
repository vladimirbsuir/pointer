package dev.pointer.testing;

import dev.pointer.container.ContainerRuntime;
import dev.pointer.container.ContainerSpec;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class FakeContainerRuntime implements ContainerRuntime {

    private final Map<String, ContainerSpec> containers = new ConcurrentHashMap<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final LinkedBlockingQueue<ContainerSpec> created = new LinkedBlockingQueue<>();
    private final List<String> starts = new CopyOnWriteArrayList<>();
    private final List<String> removed = new CopyOnWriteArrayList<>();

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

    public ContainerSpec awaitCreated(Duration timeout) throws InterruptedException {
        var spec = created.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (spec == null) {
            throw new AssertionError("No container was created within " + timeout);
        }
        return spec;
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
