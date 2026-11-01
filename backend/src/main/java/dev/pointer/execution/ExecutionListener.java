package dev.pointer.execution;

public interface ExecutionListener {

    default void onOutput(String stream, String data) {}

    default void onWaitingForDevice() {}

    default void onDeviceAvailable() {}
}
