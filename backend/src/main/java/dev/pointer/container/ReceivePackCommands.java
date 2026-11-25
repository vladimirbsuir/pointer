package dev.pointer.container;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

record ReceivePackCommands(byte[] consumed, List<Command> commands, Set<String> capabilities, boolean signed) {

    static final int MAX_BYTES = 64 * 1024;
    private static final int LENGTH_DIGITS = 4;
    private static final int FLUSH = 0;
    private static final String SHALLOW = "shallow ";
    private static final String PUSH_CERT = "push-cert";

    record Command(String oldId, String newId, String ref) {

        boolean isDeletion() {
            return newId.chars().allMatch(character -> character == '0');
        }
    }

    static ReceivePackCommands read(InputStream input) throws IOException {
        var consumed = new ByteArrayOutputStream();
        var commands = new ArrayList<Command>();
        var capabilities = new LinkedHashSet<String>();
        var signed = false;
        while (true) {
            var header = readExactly(input, LENGTH_DIGITS, consumed);
            if (header == null) {
                break;
            }
            var length = parseLength(header);
            if (length == FLUSH) {
                break;
            }
            if (length < LENGTH_DIGITS) {
                throw new MalformedCommandsException("Unexpected packet length " + length);
            }
            var payload = readExactly(input, length - LENGTH_DIGITS, consumed);
            if (payload == null) {
                throw new MalformedCommandsException("The command list ends early");
            }
            var line = new String(payload, StandardCharsets.UTF_8);
            var separator = line.indexOf('\0');
            if (separator >= 0) {
                for (var capability : line.substring(separator + 1).strip().split(" ")) {
                    if (!capability.isBlank()) {
                        capabilities.add(capability);
                    }
                }
                line = line.substring(0, separator);
            }
            line = line.stripTrailing();
            if (line.startsWith(PUSH_CERT)) {
                signed = true;
                break;
            }
            if (line.startsWith(SHALLOW)) {
                continue;
            }
            commands.add(parseCommand(line));
        }
        return new ReceivePackCommands(consumed.toByteArray(), List.copyOf(commands), Set.copyOf(capabilities), signed);
    }

    private static Command parseCommand(String line) {
        var parts = line.split(" ", 3);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new MalformedCommandsException("Unexpected command " + line);
        }
        return new Command(parts[0], parts[1], parts[2]);
    }

    private static int parseLength(byte[] header) {
        try {
            return Integer.parseInt(new String(header, StandardCharsets.US_ASCII), 16);
        } catch (NumberFormatException exception) {
            throw new MalformedCommandsException("Unexpected packet header");
        }
    }

    private static byte[] readExactly(InputStream input, int length, ByteArrayOutputStream consumed)
            throws IOException {
        if (consumed.size() + length > MAX_BYTES) {
            throw new MalformedCommandsException("The command list is longer than " + MAX_BYTES + " bytes");
        }
        var bytes = input.readNBytes(length);
        consumed.write(bytes);
        if (bytes.length == 0 && length > 0) {
            return null;
        }
        if (bytes.length < length) {
            throw new MalformedCommandsException("The command list ends early");
        }
        return bytes;
    }

    static final class MalformedCommandsException extends RuntimeException {

        MalformedCommandsException(String message) {
            super(message);
        }
    }
}
