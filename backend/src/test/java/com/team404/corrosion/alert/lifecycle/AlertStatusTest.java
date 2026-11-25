package com.team404.corrosion.alert.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class AlertStatusTest {

    @Test
    void onlyAnOpenAlertCanBeAcknowledged() {
        assertThat(AlertStatus.OPEN.canAcknowledge()).isTrue();
        assertThat(AlertStatus.ACKNOWLEDGED.canAcknowledge()).isFalse();
        assertThat(AlertStatus.RESOLVED.canAcknowledge()).isFalse();
    }

    @Test
    void openAndAcknowledgedAlertsCanBeResolved() {
        assertThat(AlertStatus.OPEN.canResolve()).isTrue();
        assertThat(AlertStatus.ACKNOWLEDGED.canResolve()).isTrue();
        assertThat(AlertStatus.RESOLVED.canResolve()).isFalse();
    }

    @Test
    void aResolvedAlertTakesNoMoreActions() {
        assertThat(AlertStatus.OPEN.canLogAction()).isTrue();
        assertThat(AlertStatus.ACKNOWLEDGED.canLogAction()).isTrue();
        assertThat(AlertStatus.RESOLVED.canLogAction()).isFalse();
    }

    @Test
    void onlyAnOpenAlertEscalates() {
        assertThat(AlertStatus.OPEN.canEscalate()).isTrue();
        assertThat(AlertStatus.ACKNOWLEDGED.canEscalate()).isFalse();
        assertThat(AlertStatus.RESOLVED.canEscalate()).isFalse();
    }

    @Test
    void sqlGuardsListExactlyTheAllowedStatuses() {
        assertThat(AlertStatus.sqlIn(AlertStatus::canAcknowledge)).isEqualTo("('OPEN')");
        assertThat(AlertStatus.sqlIn(AlertStatus::canResolve)).isEqualTo("('OPEN','ACKNOWLEDGED')");
    }

    @Test
    void statusFilterMapsToStatuses() {
        assertThat(AlertService.statuses(null)).isEmpty();
        assertThat(AlertService.statuses("ALL")).isEmpty();
        assertThat(AlertService.statuses("active")).containsExactly(AlertStatus.OPEN, AlertStatus.ACKNOWLEDGED);
        assertThat(AlertService.statuses("RESOLVED")).isEqualTo(List.of(AlertStatus.RESOLVED));
        assertThatThrownBy(() -> AlertService.statuses("CLOSED")).isInstanceOf(AlertException.class);
    }
}
