package com.team404.corrosion.analytics.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.team404.corrosion.analytics.risk.TimeOfWetness.sample;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

public class TimeOfWetnessTest {
    private static final Instant to = Instant.parse("2026-11-05T10:00:00Z");
    private static final Duration max_gap = Duration.ofMinutes(5);
    private static sample at (int minutes, double tempC, double rhPct){
        return new sample(to.plusSeconds(minutes*60L), tempC, rhPct);
    }

    @Test
    void unsortedSamplesAreRejected() {
        List<sample> samples= List.of(at(10, 20, 90), at(0, 20, 90));
        assertThrows(IllegalArgumentException.class, () -> TimeOfWetness.wetMinutes(samples, max_gap));
    }

    @Test
    void wetNeedsHumidityAbove80AndTemperatureAbove0() {
        assertTrue(TimeOfWetness.isWet(20, 85));
        assertFalse(TimeOfWetness.isWet(20, 80));
        assertFalse(TimeOfWetness.isWet(20, 60));
        assertFalse(TimeOfWetness.isWet(0, 90));
        assertFalse(TimeOfWetness.isWet(-5, 90));
    }

    @Test
    void emptyAndSingleSampleGiveZero() {
        assertEquals(0.0, TimeOfWetness.wetMinutes(List.of(), max_gap), 1e-12);
        assertEquals(0.0, TimeOfWetness.wetMinutes(List.of(at(0, 20, 90)), max_gap), 1e-12);
    }

    @Test
    void countsTheTimeUntilTheNextReadingForWetReadingsOnly() {
        List<sample> samples = List.of(
                at(0, 20, 90),
                at(2, 20, 90),
                at(5, 20, 50),
                at(8, 20, 50));
        assertEquals(5.0, TimeOfWetness.wetMinutes(samples, max_gap), 1e-9);
    }

    @Test
    void longGapIsCappedSoAnOutageIsNotCountedAsWetTime() {
        List<sample> samples = List.of(at(0, 20, 90), at(120, 20, 90));
        assertEquals(5.0, TimeOfWetness.wetMinutes(samples, max_gap), 1e-9);
    }
}
