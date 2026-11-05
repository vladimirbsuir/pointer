package dev.pointer.container;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("pointer.container")
record ContainerProperties(
        boolean enabled,
        String dockerHost,
        String image,
        String apiUrl,
        String network,
        double cpus,
        DataSize memory,
        long pidsLimit,
        Duration idleTimeout,
        Duration prepareTimeout,
        String githubApiUrl) {}
