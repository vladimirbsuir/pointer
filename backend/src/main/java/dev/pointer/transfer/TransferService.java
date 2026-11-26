package dev.pointer.transfer;

import dev.pointer.common.crypto.Tokens;
import dev.pointer.common.error.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@EnableConfigurationProperties(TransferProperties.class)
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    static final String PATH = "/api/v1/transfers/";
    private static final int TICKET_BYTES = 32;
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final HttpStatus CONTENT_TOO_LARGE = HttpStatus.valueOf(413);

    private final Map<String, Transfer> uploads = new ConcurrentHashMap<>();
    private final Map<String, Transfer> downloads = new ConcurrentHashMap<>();
    private final TransferProperties properties;
    private final Clock clock;

    TransferService(TransferProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Transfer open(long maxBytes) {
        discardExpired();
        var transfer = new Transfer(
                this,
                Tokens.random(TICKET_BYTES),
                Tokens.random(TICKET_BYTES),
                temporaryFile(),
                maxBytes,
                clock.instant().plus(properties.ttl()));
        uploads.put(transfer.uploadTicket(), transfer);
        downloads.put(transfer.downloadTicket(), transfer);
        return transfer;
    }

    void receive(String ticket, InputStream body) throws IOException {
        var transfer = Optional.ofNullable(uploads.remove(ticket))
                .filter(candidate -> !candidate.isExpired(clock.instant()))
                .orElseThrow(TransferService::notFound);
        var received = 0L;
        try (var output = Files.newOutputStream(transfer.file())) {
            var buffer = new byte[BUFFER_BYTES];
            int read;
            while ((read = body.read(buffer)) != -1) {
                received += read;
                if (received > transfer.maxBytes()) {
                    throw new ApiException(
                            CONTENT_TOO_LARGE,
                            "transfer_too_large",
                            "The upload is larger than " + transfer.maxBytes() + " bytes");
                }
                output.write(buffer, 0, read);
            }
        }
        transfer.markUploaded(received);
    }

    Path download(String ticket) {
        return Optional.ofNullable(downloads.remove(ticket))
                .filter(Transfer::isUploaded)
                .filter(candidate -> !candidate.isExpired(clock.instant()))
                .map(Transfer::file)
                .orElseThrow(TransferService::notFound);
    }

    void discard(Transfer transfer) {
        uploads.remove(transfer.uploadTicket());
        downloads.remove(transfer.downloadTicket());
        try {
            Files.deleteIfExists(transfer.file());
        } catch (IOException exception) {
            log.warn("Could not delete the transfer file {}", transfer.file(), exception);
        }
    }

    private void discardExpired() {
        var now = clock.instant();
        uploads.values().stream()
                .filter(transfer -> transfer.isExpired(now))
                .toList()
                .forEach(Transfer::close);
        downloads.values().stream()
                .filter(transfer -> transfer.isExpired(now))
                .toList()
                .forEach(Transfer::close);
    }

    private static Path temporaryFile() {
        try {
            return Files.createTempFile("pointer-transfer-", ".bin");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static ApiException notFound() {
        return ApiException.notFound("transfer_not_found", "The transfer does not exist or has expired");
    }
}
