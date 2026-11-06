package com.team404.corrosion.alert;

/** Pushes a small change notice to the dashboard (UI convenience only: the pages also poll). */
public interface AlertBroadcaster {

    String TOPIC = "/topic/alerts";

    void publish(String topic, Object payload);
}
