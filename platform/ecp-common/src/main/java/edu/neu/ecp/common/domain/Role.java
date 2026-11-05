package edu.neu.ecp.common.domain;

/** The four roles from the proposal. Permission matrix: docs/ARCHITECTURE.md §6. */
public enum Role {
    /** Acts on alerts and logs corrective actions. */
    TECHNICIAN,
    /** Monitors the dashboard (read-only live view). */
    OPERATOR,
    /** Read-only trends, reports and audit log. */
    MANAGER,
    /** Users, sensors, equipment profiles, simulator control. */
    ADMIN
}
