package dev.pointer.container;

public interface ContainerRuntime {

    boolean isAvailable();

    boolean exists(String name);

    void create(ContainerSpec spec);

    void start(String name);

    void stop(String name);

    void remove(String name, String volume);
}
