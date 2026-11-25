package dev.pointer.container;

import dev.pointer.common.crypto.SecretCipher;
import dev.pointer.session.SessionService;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DesktopViewProperties.class)
class DesktopViewConfiguration implements WebSocketConfigurer {

    private final ObjectProvider<DesktopStreamHandler> handler;
    private final String[] allowedOrigins;

    DesktopViewConfiguration(
            ObjectProvider<DesktopStreamHandler> handler,
            @Value("${pointer.auth.allowed-origins}") String[] allowedOrigins) {
        this.handler = handler;
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    static DesktopTickets desktopTickets(DesktopViewProperties properties, Clock clock) {
        return new DesktopTickets(properties.ticketTtl(), clock);
    }

    @Bean
    static DesktopViewService desktopViewService(
            DesktopViewProperties properties,
            RemoteSessionService remote,
            RemoteWorkspaceRepository workspaces,
            SessionService sessions,
            ContainerRuntime runtime,
            SecretCipher cipher,
            DesktopTickets tickets) {
        return new DesktopViewService(properties, remote, workspaces, sessions, runtime, cipher, tickets);
    }

    @Bean
    static DesktopStreamHandler desktopStreamHandler(DesktopViewService service) {
        return new DesktopStreamHandler(service);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        var streamHandler = handler.getObject();
        registry.addHandler(streamHandler, DesktopStreamHandler.PATH)
                .addInterceptors(streamHandler)
                .setAllowedOrigins(allowedOrigins);
    }
}
