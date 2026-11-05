package dev.pointer.gateway;

import dev.pointer.common.error.ApiException;
import dev.pointer.common.security.AuthPrincipal;
import dev.pointer.execution.ExecutionService;
import dev.pointer.session.LiveItemSnapshots;
import dev.pointer.session.SessionService;
import dev.pointer.tool.McpToolCatalog;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class GatewayHandler extends TextWebSocketHandler {

    static final int PROTOCOL_VERSION = 1;
    static final CloseStatus PROTOCOL_ERROR = new CloseStatus(4400, "Protocol error");
    static final CloseStatus UNAUTHORIZED = new CloseStatus(4401, "Unauthorized");
    static final CloseStatus UNSUPPORTED_VERSION = new CloseStatus(4426, "Unsupported protocol version");

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Logger log = LoggerFactory.getLogger(GatewayHandler.class);

    private final ConnectionRegistry registry;
    private final McpToolCatalog mcpTools;
    private final SessionService sessionService;
    private final LiveItemSnapshots snapshots;
    private final RelayService relay;
    private final ExecutionService execution;
    private final JwtDecoder jwtDecoder;
    private final JsonMapper json;
    private final Clock clock;

    GatewayHandler(
            ConnectionRegistry registry,
            SessionService sessionService,
            LiveItemSnapshots snapshots,
            RelayService relay,
            ExecutionService execution,
            JwtDecoder jwtDecoder,
            JsonMapper json,
            McpToolCatalog mcpTools,
            Clock clock) {
        this.registry = registry;
        this.mcpTools = mcpTools;
        this.sessionService = sessionService;
        this.snapshots = snapshots;
        this.relay = relay;
        this.execution = execution;
        this.jwtDecoder = jwtDecoder;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (!(session.getPrincipal() instanceof JwtAuthenticationToken token)) {
            session.close(UNAUTHORIZED);
            return;
        }
        var jwt = token.getToken();
        var deviceClaim = jwt.getClaimAsString(AuthPrincipal.DEVICE_CLAIM);
        registry.add(new GatewayConnection(
                session,
                json,
                UUID.fromString(jwt.getSubject()),
                deviceClaim == null ? null : UUID.fromString(deviceClaim),
                jwt.getExpiresAt()));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        var connection = registry.get(session.getId()).orElse(null);
        if (connection == null) {
            return;
        }
        JsonNode envelope;
        try {
            envelope = json.readTree(message.getPayload());
        } catch (RuntimeException exception) {
            connection.close(PROTOCOL_ERROR);
            return;
        }
        var type = envelope.path("type").asString("");
        var id = envelope.path("id").asString(null);
        var payload = envelope.path("payload");
        if (!connection.isReady() && !"hello".equals(type)) {
            connection.close(PROTOCOL_ERROR);
            return;
        }
        try {
            switch (type) {
                case "hello" -> hello(connection, id, payload);
                case "auth.refresh" -> refreshAuth(connection, id, payload);
                case "device.projects_changed" -> connection.updateProjects(uuids(payload.path("projects")));
                case "device.tools" -> reportTools(connection, payload);
                case "session.subscribe" -> subscribe(connection, id, payload);
                case "session.unsubscribe" -> connection.unsubscribe(uuid(payload, "sessionId"));
                case "exec.output" ->
                    execution.onOutput(
                            uuid(payload, "callId"),
                            payload.path("stream").asString("stdout"),
                            payload.path("data").asString(""));
                case "exec.result" -> deliverResult(payload);
                case "relay.response.start" ->
                    relay.onStart(
                            uuid(payload, "relayId"), payload.path("status").asInt());
                case "relay.response.chunk" ->
                    relay.onChunk(uuid(payload, "relayId"), payload.path("data").asString(""));
                case "relay.response.end" -> relay.onEnd(uuid(payload, "relayId"));
                case "relay.error" ->
                    relay.onError(
                            uuid(payload, "relayId"),
                            payload.path("code").asString("relay_error"),
                            payload.path("message").asString(""));
                default -> error(connection, id, "unknown_message_type", "Unknown message type: " + type);
            }
        } catch (ApiException exception) {
            error(connection, id, exception.code(), exception.getMessage());
        } catch (IllegalArgumentException exception) {
            error(connection, id, "invalid_payload", exception.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.get(session.getId()).ifPresent(connection -> {
            registry.remove(connection);
            relay.onDisconnect(connection);
        });
    }

    @Scheduled(fixedDelay = 30_000)
    void closeExpiredConnections() {
        var now = clock.instant();
        registry.all().stream()
                .filter(connection -> connection.tokenExpiresAt() != null
                        && connection.tokenExpiresAt().isBefore(now))
                .forEach(connection -> connection.close(UNAUTHORIZED));
    }

    private void hello(GatewayConnection connection, String id, JsonNode payload) {
        if (payload.path("protocolVersion").asInt(0) != PROTOCOL_VERSION) {
            connection.close(UNSUPPORTED_VERSION);
            return;
        }
        var deviceId = uuid(payload, "deviceId");
        if (connection.tokenDeviceId() != null && !connection.tokenDeviceId().equals(deviceId)) {
            connection.close(PROTOCOL_ERROR);
            return;
        }
        var capabilities = new HashSet<String>();
        payload.path("capabilities").forEach(capability -> capabilities.add(capability.asString()));
        var interpreters = new HashSet<String>();
        payload.path("interpreters").forEach(interpreter -> interpreters.add(interpreter.asString()));
        connection.markReady(deviceId, capabilities, uuids(payload.path("projects")), interpreters);
        registry.registerDevice(connection);
        var welcome = new LinkedHashMap<String, Object>();
        welcome.put("connectionId", connection.id());
        welcome.put("serverVersion", "0.1.0");
        welcome.put("userId", connection.userId());
        welcome.put("limits", Map.of("maxMessageBytes", 4_194_304, "maxToolOutputBytes", 262_144));
        connection.send("welcome", id, welcome);
        payload.path("undeliveredResults").forEach(this::deliverResult);
        log.debug("Device {} connected with capabilities {}", deviceId, capabilities);
    }

    private void refreshAuth(GatewayConnection connection, String id, JsonNode payload) {
        try {
            var jwt = jwtDecoder.decode(payload.path("accessToken").asString(""));
            if (!connection.userId().toString().equals(jwt.getSubject())) {
                connection.close(UNAUTHORIZED);
                return;
            }
            connection.refreshToken(jwt.getExpiresAt());
            connection.send("auth.refreshed", id, Map.of("expiresAt", jwt.getExpiresAt()));
        } catch (JwtException exception) {
            connection.close(UNAUTHORIZED);
        }
    }

    private void subscribe(GatewayConnection connection, String id, JsonNode payload) {
        var sessionId = uuid(payload, "sessionId");
        var afterSeq = payload.path("afterSeq").asLong(0);
        var session = sessionService.owned(connection.userId(), sessionId);
        synchronized (connection) {
            connection.subscribe(sessionId);
            connection.send(
                    "session.subscribed",
                    id,
                    Map.of(
                            "sessionId",
                            sessionId,
                            "lastSeq",
                            sessionService.currentSeq(sessionId),
                            "status",
                            session.getStatus()));
            for (var event : sessionService.eventsAfter(sessionId, afterSeq)) {
                var eventPayload = new LinkedHashMap<String, Object>();
                eventPayload.put("sessionId", event.sessionId());
                eventPayload.put("seq", event.seq());
                eventPayload.put("occurredAt", event.occurredAt());
                eventPayload.put("event", event.event());
                connection.send("session.event", null, eventPayload);
            }
            for (var snapshot : snapshots.snapshots(sessionId)) {
                connection.send("session.snapshot", null, Map.of("sessionId", sessionId, "item", snapshot));
            }
        }
    }

    private void reportTools(GatewayConnection connection, JsonNode payload) {
        var reported = new ArrayList<McpToolCatalog.ReportedTool>();
        for (var tool : payload.path("tools")) {
            var server = tool.path("server").asString("");
            var name = tool.path("name").asString("");
            if (server.isBlank() || name.isBlank()) {
                continue;
            }
            Map<String, Object> schema = tool.path("inputSchema").isObject()
                    ? json.convertValue(tool.path("inputSchema"), MAP_TYPE)
                    : Map.of("type", "object");
            reported.add(new McpToolCatalog.ReportedTool(
                    server,
                    name,
                    tool.path("description").asString(""),
                    schema,
                    tool.path("readOnly").asBoolean(false)));
        }
        mcpTools.report(connection.userId(), connection.deviceId(), reported);
    }

    private void deliverResult(JsonNode payload) {
        Map<String, Object> result = json.convertValue(payload, MAP_TYPE);
        execution.onResult(uuid(payload, "callId"), ExecutionService.parseResult(result));
    }

    private static void error(GatewayConnection connection, String replyTo, String code, String message) {
        connection.send("error", replyTo, Map.of("code", code, "message", message, "retryable", false));
    }

    private static UUID uuid(JsonNode payload, String field) {
        var value = payload.path(field).asString(null);
        if (value == null) {
            throw new IllegalArgumentException("Missing " + field);
        }
        return UUID.fromString(value);
    }

    private static Set<UUID> uuids(JsonNode array) {
        var result = new HashSet<UUID>();
        array.forEach(node -> result.add(UUID.fromString(node.asString())));
        return result;
    }
}
