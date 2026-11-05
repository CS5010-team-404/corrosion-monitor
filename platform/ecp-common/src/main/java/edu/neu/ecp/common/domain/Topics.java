package edu.neu.ecp.common.domain;

/** STOMP topics the dashboard subscribes to. Payloads are documented in docs/ARCHITECTURE.md §5. */
public final class Topics {
    public static final String READINGS = "/topic/readings";
    public static final String RISK = "/topic/risk";
    public static final String HEALTH = "/topic/health";
    public static final String ALERTS = "/topic/alerts";
    public static final String SERVICES = "/topic/services";

    private Topics() {
    }
}
