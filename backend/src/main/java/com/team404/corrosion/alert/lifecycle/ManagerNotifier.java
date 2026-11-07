package com.team404.corrosion.alert.lifecycle;

import com.team404.corrosion.alert.lifecycle.AlertViews.AlertView;
import java.util.List;

/** Tells the managers that an alert nobody acknowledged has escalated. Must not throw: escalation is already saved. */
public interface ManagerNotifier {

    void escalated(AlertView alert, List<String> managerEmails);
}
