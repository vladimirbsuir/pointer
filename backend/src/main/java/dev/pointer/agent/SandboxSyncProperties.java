package dev.pointer.agent;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("pointer.sandbox.sync")
record SandboxSyncProperties(int maxFiles, DataSize maxBytes, Duration timeout) {}
