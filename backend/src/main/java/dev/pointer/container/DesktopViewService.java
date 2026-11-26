package dev.pointer.container;

import dev.pointer.common.crypto.SecretCipher;
import dev.pointer.common.error.ApiException;
import dev.pointer.session.SessionService;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

public class DesktopViewService {

    static final String STATUS_OPERATION = "pointer.desktop_status";

    private static final Duration STATUS_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final DesktopViewProperties properties;
    private final RemoteSessionService remote;
    private final RemoteWorkspaceRepository workspaces;
    private final SessionService sessions;
    private final ContainerRuntime runtime;
    private final SecretCipher cipher;
    private final DesktopTickets tickets;
    private final DesktopViewers viewers = new DesktopViewers();
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    DesktopViewService(
            DesktopViewProperties properties,
            RemoteSessionService remote,
            RemoteWorkspaceRepository workspaces,
            SessionService sessions,
            ContainerRuntime runtime,
            SecretCipher cipher,
            DesktopTickets tickets) {
        this.properties = properties;
        this.remote = remote;
        this.workspaces = workspaces;
        this.sessions = sessions;
        this.runtime = runtime;
        this.cipher = cipher;
        this.tickets = tickets;
    }

    DesktopViewDtos.DesktopStatus status(UUID userId, UUID sessionId) {
        sessions.get(userId, sessionId);
        var workspace = workspaces
                .findBySessionId(sessionId)
                .filter(candidate -> candidate.getUserId().equals(userId))
                .orElse(null);
        if (workspace == null || !isAvailable(workspace)) {
            return new DesktopViewDtos.DesktopStatus(false, false, null, null);
        }
        var display = display(workspace);
        return new DesktopViewDtos.DesktopStatus(true, display.running(), display.width(), display.height());
    }

    DesktopViewDtos.DesktopTicket issueTicket(UUID userId, UUID sessionId) {
        var workspace = owned(userId, sessionId);
        if (!isAvailable(workspace)) {
            throw ApiException.conflict("desktop_unavailable", "The session has no live view");
        }
        if (!remote.isContainerRunning(workspace)) {
            throw ApiException.conflict("container_not_running", "The session container is not running");
        }
        var display = display(workspace);
        if (!display.running()) {
            throw ApiException.conflict("desktop_not_running", "The agent has not started a display yet");
        }
        if (viewers.count(sessionId) >= properties.maxViewers()) {
            throw ApiException.tooManyRequests("too_many_viewers", "The display already has the maximum of viewers");
        }
        var ticket = tickets.issue(userId, sessionId);
        return new DesktopViewDtos.DesktopTicket(
                ticket.value(),
                ticket.expiresAt(),
                streamPath(sessionId),
                cipher.decrypt(workspace.getDesktopViewSecret()),
                display.width(),
                display.height());
    }

    boolean redeem(String ticket, UUID sessionId) {
        return tickets.redeem(ticket, sessionId).isPresent();
    }

    DesktopViewer open(WebSocketSession session, UUID sessionId) {
        var viewer = new DesktopViewer(
                session, sessionId, viewers, properties.viewerBuffer().toBytes(), properties.viewerSendTimeout());
        if (!viewers.register(sessionId, viewer, properties.maxViewers())) {
            viewer.close(DesktopViewer.TOO_MANY_VIEWERS);
            return viewer;
        }
        var address = streamAddress(sessionId);
        if (address.isEmpty()) {
            viewer.close(DesktopViewer.DISPLAY_UNREACHABLE);
            return viewer;
        }
        viewer.connect(client, address.get());
        return viewer;
    }

    private Optional<URI> streamAddress(UUID sessionId) {
        try {
            return workspaces
                    .findBySessionId(sessionId)
                    .filter(RemoteWorkspace::hasDesktopView)
                    .flatMap(workspace -> runtime.streamAddress(workspace.getContainerName()));
        } catch (ContainerException exception) {
            return Optional.empty();
        }
    }

    void closeViewers(UUID sessionId, CloseStatus status) {
        viewers.closeAll(sessionId, status);
    }

    static String streamPath(UUID sessionId) {
        return "/api/v1/sessions/" + sessionId + "/desktop/stream";
    }

    private boolean isAvailable(RemoteWorkspace workspace) {
        return remote.isEnabled() && workspace.hasDesktopView() && remote.hasDesktop(workspace);
    }

    private Display display(RemoteWorkspace workspace) {
        return remote.runIfRunning(workspace, STATUS_OPERATION, STATUS_TIMEOUT)
                .map(DesktopViewService::toDisplay)
                .orElse(Display.STOPPED);
    }

    private static Display toDisplay(Map<String, Object> status) {
        var running = Boolean.TRUE.equals(status.get("running")) && Boolean.TRUE.equals(status.get("stream"));
        return running ? new Display(true, number(status.get("width")), number(status.get("height"))) : Display.STOPPED;
    }

    private static Integer number(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private RemoteWorkspace owned(UUID userId, UUID sessionId) {
        sessions.get(userId, sessionId);
        return workspaces
                .findBySessionId(sessionId)
                .filter(workspace -> workspace.getUserId().equals(userId))
                .orElseThrow(
                        () -> ApiException.notFound("remote_session_not_found", "The session is not on the server"));
    }

    private record Display(boolean running, Integer width, Integer height) {

        static final Display STOPPED = new Display(false, null, null);
    }
}
