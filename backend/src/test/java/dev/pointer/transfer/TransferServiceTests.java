package dev.pointer.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pointer.common.error.ApiException;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TransferServiceTests {

    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");

    private final MutableClock clock = new MutableClock();
    private final TransferService service = new TransferService(new TransferProperties(Duration.ofMinutes(10)), clock);

    @Test
    void anUploadCanBeDownloadedOnceAndClosingDeletesTheFile() throws Exception {
        var transfer = service.open(1024);

        service.receive(ticket(transfer.uploadPath()), new ByteArrayInputStream(new byte[] {1, 2, 3}));
        var file = service.download(ticket(transfer.downloadPath()));

        assertThat(transfer.isUploaded()).isTrue();
        assertThat(transfer.uploadedBytes()).isEqualTo(3);
        assertThat(Files.readAllBytes(file)).containsExactly(1, 2, 3);
        assertNotFound(() -> service.download(ticket(transfer.downloadPath())));
        assertNotFound(() -> service.receive(ticket(transfer.uploadPath()), new ByteArrayInputStream(new byte[0])));
        transfer.close();
        assertThat(file).doesNotExist();
    }

    @Test
    void anUploadOverTheLimitIsRejectedAndNotMarkedUploaded() {
        var transfer = service.open(4);

        assertThatThrownBy(() -> service.receive(ticket(transfer.uploadPath()), new ByteArrayInputStream(new byte[5])))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("transfer_too_large"));
        assertThat(transfer.isUploaded()).isFalse();
        assertNotFound(() -> service.download(ticket(transfer.downloadPath())));
        transfer.close();
    }

    @Test
    void expiredTransfersAreRefusedAndDiscardedByTheNextOpen() throws Exception {
        var transfer = service.open(1024);
        service.receive(ticket(transfer.uploadPath()), new ByteArrayInputStream(new byte[] {1}));
        clock.advance(Duration.ofMinutes(11));

        assertNotFound(() -> service.download(ticket(transfer.downloadPath())));
        var expired = service.open(1024);
        var stale = service.open(1024);
        clock.advance(Duration.ofMinutes(11));
        service.open(1024).close();

        assertThat(stale.file()).doesNotExist();
        assertNotFound(() -> service.receive(ticket(stale.uploadPath()), new ByteArrayInputStream(new byte[0])));
        expired.close();
        transfer.close();
    }

    @Test
    void unknownTicketsAreNotFound() {
        assertNotFound(() -> service.download("unknown"));
        assertNotFound(() -> service.receive("unknown", new ByteArrayInputStream(new byte[0])));
    }

    private static String ticket(String path) {
        assertThat(path).startsWith("/api/v1/transfers/");
        return path.substring("/api/v1/transfers/".length());
    }

    private static void assertNotFound(ThrowingAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("transfer_not_found"));
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static final class MutableClock extends Clock {

        private Instant now = NOW;

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
