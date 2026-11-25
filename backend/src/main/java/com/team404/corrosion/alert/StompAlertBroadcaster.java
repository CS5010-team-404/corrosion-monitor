package com.team404.corrosion.alert;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** Sends alert change notices to the browsers subscribed to /topic/alerts. */
@Component
public class StompAlertBroadcaster implements AlertBroadcaster {

    private final SimpMessagingTemplate template;

    public StompAlertBroadcaster(SimpMessagingTemplate template) {
        this.template = template;
    }

    @Override
    public void publish(String topic, Object payload) {
        template.convertAndSend(topic, payload);
    }
}
