package com.example.chat.realtime;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Plain {@code @EnableWebSocket} (raw frames), deliberately not {@code @EnableWebSocketMessageBroker}:
 * the broker variant would hand routing, subscriptions and user destinations to Spring,
 * and those are exactly the parts this project implements itself.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatSocketHandler handler;
    private final ChatHandshakeInterceptor handshakeInterceptor;

    public WebSocketConfig(ChatSocketHandler handler, ChatHandshakeInterceptor handshakeInterceptor) {
        this.handler = handler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // No setAllowedOrigins(): the default only accepts same-origin handshakes. Browsers
        // don't apply CORS to WebSockets, so without this check any website could open a
        // socket from a visitor's browser (cross-site WebSocket hijacking).
        registry.addHandler(handler, "/ws").addInterceptors(handshakeInterceptor);
    }

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        var container = new ServletServerContainerFactoryBean();
        // Hard cap on inbound frame size, so a client can't make us buffer arbitrarily large
        // frames. 16 KB comfortably fits a 4000-char message even if every char is 4-byte UTF-8.
        container.setMaxTextMessageBufferSize(16 * 1024);
        // The browser sends an app-level ping every 25s; a socket silent for longer than this
        // is half-open (e.g. laptop lid closed) and should be reaped so we stop routing to it.
        container.setMaxSessionIdleTimeout(70_000L);
        return container;
    }
}
