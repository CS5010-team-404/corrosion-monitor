package edu.neu.ecp.common.signal;

/** Producers report conditions; the alert service decides what becomes an alert and how it is resolved. */
public enum SignalState {
    /** The condition started (or is still true with new details). */
    RAISED,
    /** The condition is no longer true. */
    CLEARED
}
