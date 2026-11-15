package dev.pointer.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MemoryCuratorTests {

    @Test
    void transcriptsKeepTheLatestPartAndToolDenials() {
        var items = List.<Map<String, Object>>of(
                Map.of("type", "user_message", "text", "x".repeat(30_000)),
                Map.of(
                        "type",
                        "tool_call",
                        "toolName",
                        "run_command",
                        "arguments",
                        Map.of("command", "rm -rf build"),
                        "toolStatus",
                        "denied",
                        "error",
                        Map.of("message", "Use the gradle clean task instead")),
                Map.of("type", "user_message", "text", "Thanks"));

        var transcript = MemoryCurator.transcript(items);

        assertThat(transcript)
                .contains("Tool run_command {command=rm -rf build} -> denied: Use the gradle clean task instead")
                .contains("User: Thanks")
                .hasSizeLessThan(MemoryCurator.MAX_TRANSCRIPT_CHARS);
    }

    @Test
    void slugsAndQueriesAreNormalized() {
        assertThat(MemoryService.slug("Backend tests need Docker Desktop running; start it first"))
                .isEqualTo("backend-tests-need-docker-desktop-running");
        assertThat(MemoryService.slug("Привет")).isEqualTo("note");
        assertThat(MemoryService.tsQuery("Deploy on Fridays?")).isEqualTo("deploy | on | fridays");
    }
}
