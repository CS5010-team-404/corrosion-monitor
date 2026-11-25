package com.team404.corrosion.alert;

import java.util.Map;

/**
 * Called once for every alert state change, inside the transaction that made the change. The implementation writes
 * the audit event with it and tells the dashboard once it commits.
 */
public interface AlertEvents {

    String OPENED = "ALERT_OPENED";
    String ACKNOWLEDGED = "ALERT_ACKNOWLEDGED";
    String RESOLVED = "ALERT_RESOLVED";
    String ESCALATED = "ALERT_ESCALATED";
    String ACTION_LOGGED = "CORRECTIVE_ACTION_LOGGED";
    /** Push-only: severity, text or conditionClearedAt changed. Not a state change, so no audit event. */
    String UPDATED = "ALERT_UPDATED";

    /** @param actor the signed-in user's email, or null when the alert service itself made the change */
    void record(String action, long alertId, String actor, Map<String, Object> details);

    /** Tells the dashboard an alert changed in a way the audit log does not record. */
    void updated(long alertId);
}
