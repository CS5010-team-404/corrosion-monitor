package edu.neu.ecp.health.domain;

/**
 * The single status shown on the dashboard. Derived from connectivity and the open issues, worst first:
 * OFFLINE > STUCK > FAULTY > DEGRADED > HEALTHY. A sensor that has never reported is UNKNOWN.
 */
public enum HealthStatus {
    HEALTHY, DEGRADED, FAULTY, STUCK, OFFLINE, UNKNOWN
}
