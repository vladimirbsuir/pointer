package dev.pointer.container;

import dev.pointer.common.crypto.Tokens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

class DesktopTickets {

    private static final int TICKET_BYTES = 32;

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    DesktopTickets(Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    Ticket issue(UUID userId, UUID sessionId) {
        var now = clock.instant();
        tickets.values().removeIf(ticket -> ticket.isExpired(now));
        var ticket = new Ticket(Tokens.random(TICKET_BYTES), userId, sessionId, now.plus(ttl));
        tickets.put(ticket.value(), ticket);
        return ticket;
    }

    Optional<Ticket> redeem(String value, UUID sessionId) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        var now = clock.instant();
        return Optional.ofNullable(tickets.remove(value))
                .filter(ticket -> !ticket.isExpired(now))
                .filter(ticket -> ticket.sessionId().equals(sessionId));
    }

    record Ticket(String value, UUID userId, UUID sessionId, Instant expiresAt) {

        boolean isExpired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }
}
