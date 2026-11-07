package edu.neu.ecp.common.audit;

public interface AuditRecorder {
    void record(AuditEvent event);
}
