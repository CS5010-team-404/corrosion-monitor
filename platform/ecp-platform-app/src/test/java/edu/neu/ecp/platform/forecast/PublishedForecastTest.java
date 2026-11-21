package edu.neu.ecp.platform.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.neu.ecp.forecast.model.Forecast;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PublishedForecastTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private static Forecast forecast(double m15, double m30, double m60, double r30, double r60, String method) {
        return new Forecast(m15, m30, m60, NOW.plusSeconds(1800), r30, r60, false, false, method, "v1");
    }

    @Test
    void copiesTheForecastRoundedToTheColumnTypes() {
        PublishedForecast p = PublishedForecast.of(7L, forecast(3.14159, 2.71828, -0.456, 0.123456, 0.98766, "model"), NOW)
                .orElseThrow();
        assertEquals(7L, p.sensorId());
        assertEquals(3.14, p.margin15C());
        assertEquals(2.72, p.margin30C());
        assertEquals(-0.46, p.margin60C());
        assertEquals(0.1235, p.warningProbability30());
        assertEquals(0.9877, p.warningProbability60());
        assertEquals("ML", p.method());
        assertEquals(NOW.plusSeconds(1800), p.predictedCondensationAt());
        assertEquals(NOW, p.computedAt());
    }

    @Test
    void namesTheMethodForAnalytics() {
        assertEquals("ML", PublishedForecast.method("model"));
        assertEquals("TREND", PublishedForecast.method("trend"));
        assertEquals("TREND", PublishedForecast.method("TREND"));
        assertThrows(IllegalArgumentException.class, () -> PublishedForecast.method("guess"));
        assertThrows(IllegalArgumentException.class, () -> PublishedForecast.method(null));
    }

    @Test
    void clampsValuesThatWouldNotFitTheColumns() {
        PublishedForecast p = PublishedForecast.of(1L, forecast(12_345, -99_999, 5, 1.7, -0.2, "trend"), NOW).orElseThrow();
        assertEquals(999.0, p.margin15C());
        assertEquals(-999.0, p.margin30C());
        assertEquals(1.0, p.warningProbability30());
        assertEquals(0.0, p.warningProbability60());
    }

    @Test
    void skipsAForecastThatIsNotANumber() {
        assertTrue(PublishedForecast.of(1L, forecast(Double.NaN, 1, 1, 0, 0, "model"), NOW).isEmpty());
        assertTrue(PublishedForecast.of(1L, forecast(1, 1, 1, Double.POSITIVE_INFINITY, 0, "model"), NOW).isEmpty());
    }

    @Test
    void noCondensationWithinTheHourStaysNull() {
        Forecast f = new Forecast(5, 5, 5, null, 0, 0, false, false, "model", "v1");
        assertNull(PublishedForecast.of(1L, f, NOW).orElseThrow().predictedCondensationAt());
    }
}
