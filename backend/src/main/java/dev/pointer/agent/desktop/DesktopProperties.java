package dev.pointer.agent.desktop;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("pointer.desktop")
public record DesktopProperties(
        int defaultWidth,
        int defaultHeight,
        int maxImageWidth,
        int maxImageHeight,
        DataSize maxImageBytes,
        long settleMs,
        int keepImages,
        int imageMaskBlock,
        int pixelsPerToken) {}
