package dev.pointer.transfer;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pointer.transfer")
record TransferProperties(Duration ttl) {}
