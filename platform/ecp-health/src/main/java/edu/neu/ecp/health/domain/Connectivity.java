package edu.neu.ecp.health.domain;

/** Is data arriving on time? Judged from the time since the last accepted reading. */
public enum Connectivity {
    /** Last reading arrived within {@code lateFactor} × expected interval. */
    ONLINE,
    /** Overdue, but not yet long enough to call the sensor offline. */
    LATE,
    /** Silent for longer than {@code offlineFactor} × expected interval. */
    OFFLINE,
    /** Registered but has never sent an accepted reading. */
    NEVER_SEEN
}
