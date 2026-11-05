package edu.neu.ecp.common.live;

/**
 * Pushes a change to the dashboard. The app module implements it with STOMP over WebSocket.
 * This is a UI convenience only: services never depend on each other through it (they share data via the database).
 */
public interface LiveUpdates {
    void publish(String topic, Object payload);
}
