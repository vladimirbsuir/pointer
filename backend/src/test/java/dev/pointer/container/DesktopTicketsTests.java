package dev.pointer.container;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DesktopTicketsTests {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-25T12:00:00Z"));
    private final DesktopTickets tickets = new DesktopTickets(Duration.ofSeconds(60), clock);
    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @Test
    void aTicketWorksOnceForItsSession() {
        var ticket = tickets.issue(userId, sessionId);

        assertThat(ticket.expiresAt()).isEqualTo(clock.instant().plusSeconds(60));
        assertThat(tickets.redeem(ticket.value(), sessionId))
                .hasValueSatisfying(redeemed -> assertThat(redeemed.userId()).isEqualTo(userId));
        assertThat(tickets.redeem(ticket.value(), sessionId)).isEmpty();
    }

    @Test
    void aTicketForAnotherSessionIsRefusedAndSpent() {
        var ticket = tickets.issue(userId, sessionId);

        assertThat(tickets.redeem(ticket.value(), UUID.randomUUID())).isEmpty();
        assertThat(tickets.redeem(ticket.value(), sessionId)).isEmpty();
    }

    @Test
    void aTicketExpiresAfterSixtySeconds() {
        var used = tickets.issue(userId, sessionId);
        var late = tickets.issue(userId, sessionId);

        clock.advance(Duration.ofSeconds(59));
        assertThat(tickets.redeem(used.value(), sessionId)).isPresent();
        clock.advance(Duration.ofSeconds(1));
        assertThat(tickets.redeem(late.value(), sessionId)).isEmpty();
        assertThat(tickets.redeem(null, sessionId)).isEmpty();
        assertThat(tickets.redeem("", sessionId)).isEmpty();
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
