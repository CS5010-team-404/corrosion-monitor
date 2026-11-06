package com.team404.corrosion.web;

import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over plain WebSocket at /ws, like the platform's. Payloads are small change notices that tell the UI to
 * refetch: /topic/alerts (alerts), /topic/risk (analytics, when it publishes). Pages also poll, so the UI works
 * without this. The handshake is open (SecurityConfig) because a browser cannot add a JWT header to it, so nothing
 * sensitive may go on a topic. Browsers only subscribe: a client SEND is dropped, because the simple broker would
 * otherwise forward it to every subscriber of the topic it names.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

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

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor stomp = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (stomp != null && StompCommand.SEND.equals(stomp.getCommand())) {
                    log.debug("Dropped a client SEND to {}", stomp.getDestination());
                    return null; // dropped without closing the session
                }
                return message;
            }
        });
    }
}
