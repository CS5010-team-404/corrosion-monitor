package edu.neu.ecp.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.neu.ecp.common.domain.Hmac;
import edu.neu.ecp.common.domain.Psychrometrics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SimulatedSensorTest {

    private static final Instant T0 = Instant.parse("2026-11-02T08:00:00Z");
    private static final Duration STEP = Duration.ofSeconds(15);

    private static SimulatedSensor sensor() {
        return new SimulatedSensor("SIM-001", "secret", 22.0, 48.0, 1.5, 42L, 0);
    }

    private static List<SimulatedReading> run(SimulatedSensor s, int ticks) {
        List<SimulatedReading> out = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            s.next(T0.plus(STEP.multipliedBy(i))).ifPresent(out::add);
        }
        return out;
    }

    @Test
    void normalReadingsArePlausibleAndVary() {
        List<SimulatedReading> rs = run(sensor(), 200);
        assertEquals(200, rs.size());
        assertTrue(rs.stream().allMatch(r -> r.temperatureC() > 20 && r.temperatureC() < 24), "temperature drifted");
        assertTrue(rs.stream().allMatch(r -> r.humidityPct() > 40 && r.humidityPct() < 56), "humidity drifted");
        assertTrue(rs.stream().map(SimulatedReading::humidityPct).distinct().count() > 50, "values should vary");
        assertEquals(200L, rs.get(199).seq());
    }

    @Test
    void humidNightPushesHumidityIntoTimeOfWetness() {
        SimulatedSensor s = sensor();
        s.setScenario(Scenario.HUMID_NIGHT, T0);
        List<SimulatedReading> rs = run(s, 160); // 40 minutes
        SimulatedReading last = rs.get(rs.size() - 1);
        assertTrue(last.humidityPct() > 80, "humidity only reached " + last.humidityPct());
    }

    @Test
    void condensationDrivesSurfaceBelowDewPoint() {
        SimulatedSensor s = sensor();
        s.setScenario(Scenario.CONDENSATION, T0);
        SimulatedReading last = run(s, 120).get(119); // 30 minutes
        double dew = Psychrometrics.dewPointC(last.temperatureC(), last.humidityPct());
        assertTrue(last.surfaceTempC() < dew, "surface " + last.surfaceTempC() + " dew " + dew);
    }

    @Test
    void silentSendsNothingAndStuckRepeatsValues() {
        SimulatedSensor s = sensor();
        run(s, 5);
        s.setScenario(Scenario.SILENT, T0);
        assertTrue(run(s, 10).isEmpty());

        s.setScenario(Scenario.NORMAL, T0);
        run(s, 1);
        s.setScenario(Scenario.STUCK, T0);
        List<SimulatedReading> stuck = run(s, 25);
        assertEquals(1L, stuck.stream().map(r -> r.temperatureC() + "/" + r.humidityPct()).distinct().count());
        assertTrue(stuck.get(24).seq() > stuck.get(0).seq(), "sequence keeps increasing");
    }

    @Test
    void lossyLeavesSequenceGaps() {
        SimulatedSensor s = sensor();
        s.setScenario(Scenario.LOSSY, T0);
        List<SimulatedReading> rs = run(s, 100);
        long expected = rs.get(rs.size() - 1).seq() - rs.get(0).seq() + 1;
        assertTrue(rs.size() < expected * 0.8, "received " + rs.size() + " of " + expected);
    }

    @Test
    void badSignatureFailsVerificationWithTheRealKey() {
        SimulatedSensor s = sensor();
        SimulatedReading r = s.next(T0).orElseThrow();
        var good = s.sign(List.of(r), 1000L);
        assertTrue(Hmac.verifyRequest("secret", 1000L, good.body(), good.signature()));
        s.setScenario(Scenario.BAD_SIGNATURE, T0);
        var bad = s.sign(List.of(r), 1000L);
        assertFalse(Hmac.verifyRequest("secret", 1000L, bad.body(), bad.signature()));
    }

    @Test
    void clockSkewShiftsMeasuredTimeAndJsonIsWellFormed() {
        SimulatedSensor s = sensor();
        s.setScenario(Scenario.CLOCK_SKEW, T0);
        Optional<SimulatedReading> r = s.next(T0);
        assertEquals(T0.minus(Duration.ofMinutes(10)), r.orElseThrow().measuredAt());
        String body = SimulatedReading.body(List.of(r.get()));
        assertTrue(body.startsWith("{\"readings\":[{\"seq\":1,\"measuredAt\":\"2026-11-02T07:50:00Z\""), body);
        assertTrue(body.contains("\"surfaceTempC\":"));
    }
}
