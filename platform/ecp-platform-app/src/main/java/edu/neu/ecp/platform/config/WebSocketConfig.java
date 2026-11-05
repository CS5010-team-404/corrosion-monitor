package edu.neu.ecp.platform.config;

import edu.neu.ecp.common.live.LiveUpdates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/** STOMP over plain WebSocket at /ws. Topics: /topic/readings, /topic/health. */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketConfig.class);
    private final String[] origins;

    public WebSocketConfig(@Value("${ecp.ws.allowed-origin-patterns:https://*.onrender.com,http://localhost:5173}") String[] origins) {
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

    @Bean
    LiveUpdates liveUpdates(SimpMessagingTemplate template) {
        return (topic, payload) -> {
            try {
                template.convertAndSend(topic, payload);
            } catch (RuntimeException e) { // a UI push must never break a service
                LOG.debug("live update to {} failed", topic, e);
            }
        };
    }
}
