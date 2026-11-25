package dev.pointer.agent;

import dev.pointer.agent.desktop.DesktopProperties;
import dev.pointer.session.AttachmentService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.util.unit.DataSize;

final class TestAssemblers {

    static final int PIXELS_PER_TOKEN = 750;

    private TestAssemblers() {}

    static ContextAssembler assembler(String now, AttachmentService attachments, MaskPolicy images) {
        return new ContextAssembler(Clock.fixed(Instant.parse(now), ZoneOffset.UTC), attachments, desktop(images));
    }

    static DesktopProperties desktop(MaskPolicy images) {
        return new DesktopProperties(
                1280, 800, 1280, 800, DataSize.ofMegabytes(2), 300, images.keep(), images.block(), PIXELS_PER_TOKEN);
    }
}
