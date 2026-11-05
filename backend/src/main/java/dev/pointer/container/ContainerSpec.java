package dev.pointer.container;

import java.util.Map;

public record ContainerSpec(String name, String volume, Map<String, String> environment, Map<String, String> labels) {}
