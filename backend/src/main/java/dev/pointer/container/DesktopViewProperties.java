package dev.pointer.container;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("pointer.desktop")
record DesktopViewProperties(int maxViewers, Duration ticketTtl, DataSize viewerBuffer, Duration viewerSendTimeout) {}
