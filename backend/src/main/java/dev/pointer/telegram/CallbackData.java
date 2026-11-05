package dev.pointer.telegram;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

record CallbackData(char action, UUID id, char option) {

    static final char SELECT_SESSION = 's';
    static final char NEW_SESSION = 'n';
    static final char APPROVAL = 'a';
    static final char PLAN = 'p';
    static final char APPROVE_ONCE = 'o';
    static final char APPROVE_SESSION = 'e';
    static final char DENY = 'd';
    static final char NO_OPTION = '-';

    private static final int ENCODED_ID_LENGTH = 22;

    static String encode(char action, UUID id) {
        return encode(action, id, NO_OPTION);
    }

    static String encode(char action, UUID id, char option) {
        var buffer = ByteBuffer.allocate(16);
        buffer.putLong(id.getMostSignificantBits());
        buffer.putLong(id.getLeastSignificantBits());
        return action + Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array()) + option;
    }

    static Optional<CallbackData> decode(String data) {
        if (data == null || data.length() != ENCODED_ID_LENGTH + 2) {
            return Optional.empty();
        }
        try {
            var buffer = ByteBuffer.wrap(Base64.getUrlDecoder().decode(data.substring(1, ENCODED_ID_LENGTH + 1)));
            var id = new UUID(buffer.getLong(), buffer.getLong());
            return Optional.of(new CallbackData(data.charAt(0), id, data.charAt(data.length() - 1)));
        } catch (IllegalArgumentException | BufferUnderflowException exception) {
            return Optional.empty();
        }
    }
}
