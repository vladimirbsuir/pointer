package dev.pointer.container;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

class DesktopStreamHandler extends AbstractWebSocketHandler implements SubProtocolCapable, HandshakeInterceptor {

    static final String PATH = "/api/v1/sessions/*/desktop/stream";

    private static final String SESSION_ATTRIBUTE = "desktopSessionId";
    private static final String VIEWER_ATTRIBUTE = "desktopViewer";

    private final DesktopViewService service;

    DesktopStreamHandler(DesktopViewService service) {
        this.service = service;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler handler,
            Map<String, Object> attributes) {
        var sessionId = sessionId(request.getURI().getPath());
        var ticket = UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .getFirst("ticket");
        if (sessionId.isEmpty() || !service.redeem(ticket, sessionId.get())) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        attributes.put(SESSION_ATTRIBUTE, sessionId.get());
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) {}

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        var sessionId = (UUID) session.getAttributes().get(SESSION_ATTRIBUTE);
        session.getAttributes().put(VIEWER_ATTRIBUTE, service.open(session, sessionId));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        var viewer = viewer(session);
        if (viewer != null) {
            viewer.forward(message);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        var viewer = viewer(session);
        if (viewer != null) {
            viewer.close(status);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        var viewer = viewer(session);
        if (viewer != null) {
            viewer.close(CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    public boolean supportsPartialMessages() {
        return true;
    }

    @Override
    public List<String> getSubProtocols() {
        return List.of(DesktopViewer.BINARY_PROTOCOL);
    }

    private static DesktopViewer viewer(WebSocketSession session) {
        return (DesktopViewer) session.getAttributes().get(VIEWER_ATTRIBUTE);
    }

    static Optional<UUID> sessionId(String path) {
        var parts = path.split("/");
        for (var index = 0; index + 1 < parts.length; index++) {
            if (parts[index].equals("sessions")) {
                try {
                    return Optional.of(UUID.fromString(parts[index + 1]));
                } catch (IllegalArgumentException exception) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }
}
