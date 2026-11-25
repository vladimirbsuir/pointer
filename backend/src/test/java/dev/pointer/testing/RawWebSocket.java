package dev.pointer.testing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public final class RawWebSocket implements AutoCloseable {

    public static final int BINARY = 2;
    public static final int CLOSE = 8;

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;
    private final boolean masking;
    private final Map<String, String> headers;
    private final int status;

    private RawWebSocket(Socket socket, boolean masking, int status, Map<String, String> headers) throws IOException {
        this.socket = socket;
        this.input = socket.getInputStream();
        this.output = socket.getOutputStream();
        this.masking = masking;
        this.status = status;
        this.headers = headers;
    }

    public static RawWebSocket connect(int port, String pathAndQuery, String protocol, String origin)
            throws IOException {
        var socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(10_000);
        var request = new StringBuilder()
                .append("GET ")
                .append(pathAndQuery)
                .append(" HTTP/1.1\r\nHost: localhost:")
                .append(port)
                .append("\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n")
                .append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n");
        if (protocol != null) {
            request.append("Sec-WebSocket-Protocol: ").append(protocol).append("\r\n");
        }
        if (origin != null) {
            request.append("Origin: ").append(origin).append("\r\n");
        }
        request.append("\r\n");
        socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
        var head = readHead(socket.getInputStream());
        var status = Integer.parseInt(head.getFirst().split(" ")[1]);
        return new RawWebSocket(socket, true, status, parseHeaders(head));
    }

    public int status() {
        return status;
    }

    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    public void sendBinary(byte[] payload) throws IOException {
        writeFrame(BINARY, payload);
    }

    public Frame read() throws IOException {
        var message = new ByteArrayOutputStream();
        var opcode = -1;
        while (true) {
            var first = input.read();
            if (first < 0) {
                throw new IOException("The connection closed");
            }
            var second = input.read();
            var length = (long) (second & 0x7f);
            if (length == 126) {
                length = ByteBuffer.wrap(input.readNBytes(2)).getShort() & 0xffff;
            } else if (length == 127) {
                length = ByteBuffer.wrap(input.readNBytes(8)).getLong();
            }
            var mask = (second & 0x80) != 0 ? input.readNBytes(4) : null;
            var payload = input.readNBytes((int) length);
            if (mask != null) {
                for (var index = 0; index < payload.length; index++) {
                    payload[index] ^= mask[index % 4];
                }
            }
            if ((first & 0x0f) != 0) {
                opcode = first & 0x0f;
            }
            message.write(payload);
            if ((first & 0x80) != 0) {
                return new Frame(opcode, message.toByteArray());
            }
        }
    }

    public int closeCode() throws IOException {
        while (true) {
            var frame = read();
            if (frame.opcode() == CLOSE) {
                return frame.payload().length >= 2
                        ? ByteBuffer.wrap(frame.payload()).getShort() & 0xffff
                        : 1005;
            }
        }
    }

    public boolean isClosedByPeer() {
        try {
            closeCode();
            return true;
        } catch (IOException exception) {
            return true;
        }
    }

    private synchronized void writeFrame(int opcode, byte[] payload) throws IOException {
        var frame = new ByteArrayOutputStream();
        frame.write(0x80 | opcode);
        var maskBit = masking ? 0x80 : 0;
        if (payload.length < 126) {
            frame.write(maskBit | payload.length);
        } else if (payload.length < 65_536) {
            frame.write(maskBit | 126);
            frame.write(ByteBuffer.allocate(2).putShort((short) payload.length).array());
        } else {
            frame.write(maskBit | 127);
            frame.write(ByteBuffer.allocate(8).putLong(payload.length).array());
        }
        if (masking) {
            var mask = new byte[4];
            ThreadLocalRandom.current().nextBytes(mask);
            frame.write(mask);
            var masked = payload.clone();
            for (var index = 0; index < masked.length; index++) {
                masked[index] ^= mask[index % 4];
            }
            frame.write(masked);
        } else {
            frame.write(payload);
        }
        output.write(frame.toByteArray());
        output.flush();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    private static List<String> readHead(InputStream input) throws IOException {
        var head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
            var next = input.read();
            if (next < 0) {
                break;
            }
            head.write(next);
        }
        return List.of(head.toString(StandardCharsets.US_ASCII).split("\r\n"));
    }

    private static Map<String, String> parseHeaders(List<String> head) {
        var headers = new LinkedHashMap<String, String>();
        for (var line : head.subList(1, head.size())) {
            var colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(
                        line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    public record Frame(int opcode, byte[] payload) {}

    public static final class Server implements AutoCloseable {

        public static final byte[] GREETING = "RFB 003.008\n".getBytes(StandardCharsets.US_ASCII);

        private final ServerSocket server;
        private final List<RawWebSocket> connections = new CopyOnWriteArrayList<>();
        private final List<String> protocols = new CopyOnWriteArrayList<>();
        private final BlockingQueue<byte[]> received = new LinkedBlockingQueue<>();

        public Server() throws IOException {
            server = new ServerSocket(0);
            Thread.ofVirtual().start(this::accept);
        }

        public int port() {
            return server.getLocalPort();
        }

        public int connectionCount() {
            return connections.size();
        }

        public List<String> protocols() {
            return List.copyOf(protocols);
        }

        public byte[] awaitReceived() throws InterruptedException {
            return received.poll(10, TimeUnit.SECONDS);
        }

        public void sendToAll(byte[] payload) throws IOException {
            for (var connection : connections) {
                connection.writeFrame(BINARY, payload);
            }
        }

        public void sendToAllQuietly(byte[] payload) {
            for (var connection : connections) {
                try {
                    connection.writeFrame(BINARY, payload);
                } catch (IOException exception) {
                    connections.remove(connection);
                }
            }
        }

        public void dropConnections() throws IOException {
            for (var connection : connections) {
                connection.close();
            }
            connections.clear();
        }

        @Override
        public void close() throws IOException {
            dropConnections();
            server.close();
        }

        private void accept() {
            while (!server.isClosed()) {
                try {
                    var socket = server.accept();
                    Thread.ofVirtual().start(() -> serve(socket));
                } catch (IOException exception) {
                    return;
                }
            }
        }

        private void serve(Socket socket) {
            try {
                var head = readHead(socket.getInputStream());
                var headers = parseHeaders(head);
                var key = headers.get("sec-websocket-key");
                var accept = Base64.getEncoder()
                        .encodeToString(MessageDigest.getInstance("SHA-1")
                                .digest((key + GUID).getBytes(StandardCharsets.US_ASCII)));
                var protocol = headers.get("sec-websocket-protocol");
                protocols.add(protocol == null ? "" : protocol);
                var response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n"
                        + (protocol == null ? "" : "Sec-WebSocket-Protocol: " + protocol + "\r\n")
                        + "\r\n";
                socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                var connection = new RawWebSocket(socket, false, 101, headers);
                connections.add(connection);
                connection.writeFrame(BINARY, GREETING);
                while (true) {
                    var frame = connection.read();
                    if (frame.opcode() == CLOSE) {
                        break;
                    }
                    received.add(frame.payload());
                }
            } catch (Exception exception) {
                connections.removeIf(connection -> connection.socket == socket);
            }
        }
    }
}
