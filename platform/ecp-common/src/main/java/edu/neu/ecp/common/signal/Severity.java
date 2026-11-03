package edu.neu.ecp.common.signal;

public enum Severity {
    INFO, WARNING, CRITICAL;

    public boolean isAtLeast(Severity other) {
        return compareTo(other) >= 0;
    }
}
