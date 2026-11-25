package dev.pointer.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ReceivePackCommandsTests {

    private static final String OLD = "a".repeat(40);
    private static final String NEW = "b".repeat(40);
    private static final String ZERO = "0".repeat(40);
    private static final String BRANCH = "pointer/1234abcd";

    @Test
    void readsCommandsAndCapabilitiesUpToTheFlushAndLeavesThePack() throws IOException {
        var body = concat(
                packet("shallow " + OLD + "\n"),
                packet(OLD + " " + NEW + " refs/heads/" + BRANCH + "\0report-status side-band-64k agent=git/2\n"),
                flush(),
                "PACK....".getBytes(StandardCharsets.US_ASCII));
        var input = new ByteArrayInputStream(body);

        var commands = ReceivePackCommands.read(input);

        assertThat(commands.commands())
                .containsExactly(new ReceivePackCommands.Command(OLD, NEW, "refs/heads/" + BRANCH));
        assertThat(commands.capabilities()).contains("report-status", "side-band-64k");
        assertThat(new String(input.readAllBytes(), StandardCharsets.US_ASCII)).isEqualTo("PACK....");
        assertThat(commands.consumed()).hasSize(body.length - "PACK....".length());
        assertThat(ReceivePackRejection.canReport(commands)).isTrue();
        assertThat(RepositoryProxy.denial(commands, BRANCH)).isNull();
    }

    @Test
    void refusesOtherRefsDeletionsAndSignedPushes() throws IOException {
        assertThat(RepositoryProxy.denial(read(OLD + " " + NEW + " refs/heads/main\0 report-status\n"), BRANCH))
                .contains("pushes to " + BRANCH + " only");
        assertThat(RepositoryProxy.denial(read(ZERO + " " + NEW + " refs/tags/v1\0 report-status\n"), BRANCH))
                .contains("pushes to " + BRANCH + " only");
        assertThat(RepositoryProxy.denial(
                        read(OLD + " " + ZERO + " refs/heads/" + BRANCH + "\0 report-status\n"), BRANCH))
                .contains("deleting");
        assertThat(RepositoryProxy.denial(read("push-cert\0 report-status\n"), BRANCH))
                .contains("Signed pushes");
        var mixed = ReceivePackCommands.read(new ByteArrayInputStream(concat(
                packet(OLD + " " + NEW + " refs/heads/" + BRANCH + "\0 report-status\n"),
                packet(OLD + " " + NEW + " refs/heads/main\n"),
                flush())));
        assertThat(RepositoryProxy.denial(mixed, BRANCH)).isNotNull();
    }

    @Test
    void anEmptyProbeIsAllowed() throws IOException {
        var commands = ReceivePackCommands.read(new ByteArrayInputStream(flush()));
        assertThat(commands.commands()).isEmpty();
        assertThat(RepositoryProxy.denial(commands, BRANCH)).isNull();
    }

    @Test
    void rejectsMalformedAndOversizedCommandLists() {
        assertThatThrownBy(() ->
                        ReceivePackCommands.read(new ByteArrayInputStream("zzzz".getBytes(StandardCharsets.US_ASCII))))
                .isInstanceOf(ReceivePackCommands.MalformedCommandsException.class);
        assertThatThrownBy(() -> ReceivePackCommands.read(
                        new ByteArrayInputStream("0010abc".getBytes(StandardCharsets.US_ASCII))))
                .isInstanceOf(ReceivePackCommands.MalformedCommandsException.class);
        var line = packet(OLD + " " + NEW + " refs/heads/" + "x".repeat(900) + "\n");
        var oversized = new ByteArrayOutputStream();
        while (oversized.size() <= ReceivePackCommands.MAX_BYTES) {
            oversized.writeBytes(line);
        }
        assertThatThrownBy(() -> ReceivePackCommands.read(new ByteArrayInputStream(oversized.toByteArray())))
                .isInstanceOf(ReceivePackCommands.MalformedCommandsException.class);
    }

    @Test
    void reportsRejectionsInTheSideBandTheClientAskedFor() throws IOException {
        var commands = read(OLD + " " + NEW + " refs/heads/main\0report-status side-band-64k\n");
        var report = new String(ReceivePackRejection.report(commands, "no"), StandardCharsets.ISO_8859_1);
        assertThat(report).contains("\u0002no\n").contains("\u0001").contains("unpack ok\n");
        assertThat(report).contains("ng refs/heads/main no\n").endsWith("0000");

        var plain = read(OLD + " " + NEW + " refs/heads/main\0report-status\n");
        assertThat(new String(ReceivePackRejection.report(plain, "no"), StandardCharsets.ISO_8859_1))
                .isEqualTo("000eunpack ok\n001ang refs/heads/main no\n0000");
    }

    private static ReceivePackCommands read(String line) throws IOException {
        return ReceivePackCommands.read(new ByteArrayInputStream(concat(packet(line), flush())));
    }

    private static byte[] packet(String line) {
        var payload = line.getBytes(StandardCharsets.UTF_8);
        return concat(String.format("%04x", payload.length + 4).getBytes(StandardCharsets.US_ASCII), payload);
    }

    private static byte[] flush() {
        return "0000".getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[]... parts) {
        var output = new ByteArrayOutputStream();
        for (var part : parts) {
            output.writeBytes(part);
        }
        return output.toByteArray();
    }
}
