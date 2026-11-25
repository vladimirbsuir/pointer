package dev.pointer.container;

import java.net.URI;
import java.util.Optional;

public interface ContainerRuntime {

    boolean isAvailable();

    boolean exists(String name);

    void create(ContainerSpec spec);

    void start(String name);

    void stop(String name);

    void remove(String name, String volume);

    boolean isOutdated(String name, ContainerSpec spec);

    void removeContainer(String name);

    Optional<URI> streamAddress(String name);
}
