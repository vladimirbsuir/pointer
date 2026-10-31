package dev.pointer.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@EnableScheduling
class GatewayConfiguration implements WebSocketConfigurer {

    private static final int MAX_TEXT_MESSAGE_BYTES = 4 * 1024 * 1024;

    private final GatewayHandler handler;

    GatewayConfiguration(GatewayHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws").setAllowedOriginPatterns("*");
    }

    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        var container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_TEXT_MESSAGE_BYTES);
        container.setMaxSessionIdleTimeout(0L);
        return container;
    }

    @Bean
    static BearerTokenResolver bearerTokenResolver(
            @Value("${pointer.gateway.allow-query-token:false}") boolean allowQueryToken) {
        var resolver = new DefaultBearerTokenResolver();
        resolver.setAllowUriQueryParameter(allowQueryToken);
        return resolver;
    }
}
