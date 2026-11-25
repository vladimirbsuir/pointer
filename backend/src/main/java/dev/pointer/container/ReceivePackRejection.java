package dev.pointer.container;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class ReceivePackRejection {

    private static final byte[] FLUSH = "0000".getBytes(StandardCharsets.US_ASCII);
    private static final int SIDE_BAND_64K_PAYLOAD = 65515;
    private static final int SIDE_BAND_PAYLOAD = 995;
    private static final byte DATA_BAND = 1;
    private static final byte MESSAGE_BAND = 2;

    private ReceivePackRejection() {}

    static boolean canReport(ReceivePackCommands commands) {
        return commands.capabilities().contains("report-status")
                || commands.capabilities().contains("report-status-v2");
    }

    static byte[] report(ReceivePackCommands commands, String reason) {
        var status = new ByteArrayOutputStream();
        status.writeBytes(packet("unpack ok\n"));
        for (var command : commands.commands()) {
            status.writeBytes(packet("ng " + command.ref() + " " + reason + "\n"));
        }
        status.writeBytes(FLUSH);
        var payloadLimit = sideBandLimit(commands);
        if (payloadLimit == 0) {
            return status.toByteArray();
        }
        var response = new ByteArrayOutputStream();
        response.writeBytes(band(MESSAGE_BAND, (reason + "\n").getBytes(StandardCharsets.UTF_8)));
        var bytes = status.toByteArray();
        for (var offset = 0; offset < bytes.length; offset += payloadLimit) {
            response.writeBytes(
                    band(DATA_BAND, Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + payloadLimit))));
        }
        response.writeBytes(FLUSH);
        return response.toByteArray();
    }

    private static int sideBandLimit(ReceivePackCommands commands) {
        if (commands.capabilities().contains("side-band-64k")) {
            return SIDE_BAND_64K_PAYLOAD;
        }
        return commands.capabilities().contains("side-band") ? SIDE_BAND_PAYLOAD : 0;
    }

    private static byte[] band(byte band, byte[] payload) {
        var content = new byte[payload.length + 1];
        content[0] = band;
        System.arraycopy(payload, 0, content, 1, payload.length);
        return packet(content);
    }

    private static byte[] packet(String text) {
        return packet(text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] packet(byte[] payload) {
        var header = String.format("%04x", payload.length + 4).getBytes(StandardCharsets.US_ASCII);
        var packet = new byte[header.length + payload.length];
        System.arraycopy(header, 0, packet, 0, header.length);
        System.arraycopy(payload, 0, packet, header.length, payload.length);
        return packet;
    }
}
