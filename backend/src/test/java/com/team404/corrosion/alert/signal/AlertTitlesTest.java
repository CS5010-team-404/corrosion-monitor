package com.team404.corrosion.alert.signal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AlertTitlesTest {

    @Test
    void condensationRiskNamesTheEquipmentAndLocation() {
        assertThat(AlertTitles.of(signal("ANALYTICS", "CONDENSATION_RISK", 7L, "risk on 7"), "Rack A1", "Server Room A"))
                .isEqualTo("Condensation risk: Rack A1, Server Room A");
    }

    @Test
    void condensationForecastNamesTheEquipmentAndLocation() {
        assertThat(AlertTitles.of(signal("ANALYTICS", "CONDENSATION_FORECAST", 7L, null), "Rack A1", "Server Room A"))
                .isEqualTo("Condensation forecast: Rack A1, Server Room A");
    }

    @Test
    void fallsBackToTheEquipmentIdOrNameAlone() {
        Signal s = signal("ANALYTICS", "CONDENSATION_RISK", 7L, null);
        assertThat(AlertTitles.of(s, null, null)).isEqualTo("Condensation risk: equipment 7");
        assertThat(AlertTitles.of(s, "Rack A1", " ")).isEqualTo("Condensation risk: Rack A1");
        assertThat(AlertTitles.of(signal("ANALYTICS", "CONDENSATION_RISK", null, null), null, null))
                .isEqualTo("Condensation risk: unknown equipment");
    }

    @Test
    void otherSignalsKeepTheirOwnTitleOrType() {
        assertThat(AlertTitles.of(signal("SENSOR_HEALTH", "STALE", 7L, "Sensor SIM-001 stopped reporting"), "Rack A1", "Room"))
                .isEqualTo("Sensor SIM-001 stopped reporting");
        assertThat(AlertTitles.of(signal("SYSTEM", "SERVICE_DOWN", null, null), null, null)).isEqualTo("SERVICE_DOWN");
        assertThat(AlertTitles.of(signal("ANALYTICS", "SOMETHING_ELSE", 7L, "Own title"), "Rack A1", "Room"))
                .isEqualTo("Own title");
        assertThat(AlertTitles.namesEquipment(signal("SENSOR_HEALTH", "CONDENSATION_RISK", 7L, null))).isFalse();
    }

    private static Signal signal(String source, String type, Long equipmentId, String title) {
        return new Signal(1, source, type, "WARNING", Signal.RAISED, equipmentId, null, null, "k", title, null,
                Instant.EPOCH);
    }
}
