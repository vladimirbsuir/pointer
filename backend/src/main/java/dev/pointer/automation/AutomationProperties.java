package dev.pointer.automation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pointer.automation")
record AutomationProperties(Duration watchInterval, Duration stallGrace) {}
