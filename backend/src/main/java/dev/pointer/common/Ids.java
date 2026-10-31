package dev.pointer.common;

import java.security.SecureRandom;
import java.util.UUID;

public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long VERSION_7 = 0x7000L;
    private static final long RAND_A_MASK = 0x0FFFL;
    private static final long VARIANT_MASK = 0x3FFFFFFFFFFFFFFFL;
    private static final long VARIANT_RFC_4122 = 0x8000000000000000L;

    private Ids() {}

    public static UUID newId() {
        long timestamp = System.currentTimeMillis();
        long mostSignificant = (timestamp << 16) | VERSION_7 | (RANDOM.nextLong() & RAND_A_MASK);
        long leastSignificant = (RANDOM.nextLong() & VARIANT_MASK) | VARIANT_RFC_4122;
        return new UUID(mostSignificant, leastSignificant);
    }
}
