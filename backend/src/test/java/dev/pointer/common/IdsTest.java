package dev.pointer.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void generatesVersion7Rfc4122Ids() {
        var id = Ids.newId();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void embedsCurrentTimestampInLeadingBits() {
        var before = System.currentTimeMillis();
        var id = Ids.newId();
        var after = System.currentTimeMillis();

        var timestamp = id.getMostSignificantBits() >>> 16;

        assertThat(timestamp).isBetween(before, after);
    }

    @Test
    void generatesDistinctIds() {
        var ids =
                IntStream.range(0, 10_000).mapToObj(i -> Ids.newId()).distinct().count();

        assertThat(ids).isEqualTo(10_000);
    }
}
