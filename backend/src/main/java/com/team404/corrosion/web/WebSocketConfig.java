package com.team404.corrosion.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over plain WebSocket at /ws, like the platform's. Payloads are small change notices that tell the UI to
 * refetch: /topic/alerts (alerts), /topic/risk (analytics, when it publishes). Pages also poll, so the UI works
 * without this. The handshake is open (SecurityConfig) because a browser cannot add a JWT header to it, so nothing
 * sensitive may go on a topic.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final String[] origins;

    public WebSocketConfig(
            @Value("${ecp.ws.allowed-origin-patterns:https://*.onrender.com,http://localhost:5173}") String[] origins) {
        this.origins = origins;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns(origins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }
}
