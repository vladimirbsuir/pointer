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
        DataSize shmSize,
        int maxRunning,
        Duration idleTimeout,
        Duration prepareTimeout,
        String githubApiUrl,
        String githubGitUrl,
        boolean desktop,
        DesktopAccess desktopAccess) {

    enum DesktopAccess {
        NETWORK,
        PUBLISHED
    }
}
