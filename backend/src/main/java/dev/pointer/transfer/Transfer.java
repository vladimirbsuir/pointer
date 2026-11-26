package dev.pointer.transfer;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class Transfer implements AutoCloseable {

    private static final long NOT_UPLOADED = -1;

    private final TransferService service;
    private final String uploadTicket;
    private final String downloadTicket;
    private final Path file;
    private final long maxBytes;
    private final Instant expiresAt;
    private final AtomicLong uploadedBytes = new AtomicLong(NOT_UPLOADED);
    private final AtomicBoolean closed = new AtomicBoolean();

    Transfer(
            TransferService service,
            String uploadTicket,
            String downloadTicket,
            Path file,
            long maxBytes,
            Instant expiresAt) {
        this.service = service;
        this.uploadTicket = uploadTicket;
        this.downloadTicket = downloadTicket;
        this.file = file;
        this.maxBytes = maxBytes;
        this.expiresAt = expiresAt;
    }

    public String uploadPath() {
        return TransferService.PATH + uploadTicket;
    }

    public String downloadPath() {
        return TransferService.PATH + downloadTicket;
    }

    public boolean isUploaded() {
        return uploadedBytes.get() != NOT_UPLOADED;
    }

    public long uploadedBytes() {
        return Math.max(uploadedBytes.get(), 0);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            service.discard(this);
        }
    }

    String uploadTicket() {
        return uploadTicket;
    }

    String downloadTicket() {
        return downloadTicket;
    }

    Path file() {
        return file;
    }

    long maxBytes() {
        return maxBytes;
    }

    boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    void markUploaded(long bytes) {
        uploadedBytes.set(bytes);
    }
}
