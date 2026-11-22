package edu.neu.ecp.platform.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import edu.neu.ecp.forecast.model.Forecast;
import edu.neu.ecp.platform.MySqlIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** FR2: the forecast publisher writes sensor_forecasts, the contract table analytics reads. */
class ForecastPublishingIntegrationTest extends MySqlIntegrationTest {

    @Autowired
    ForecastPublisher publisher;

    @Autowired
    ForecastService forecasts;

    @Autowired
    ApplicationContext context;

    @Test
    void scheduledJobIsOffInTests() {
        assertThat(context.getBeansOfType(ForecastPublishJob.class)).isEmpty();
    }

    @Test
    void publishesTheLatestForecastOfTrustedSensorsOnly() {
        Instant now = Instant.now();
        long trusted = sensor("FC-TRUSTED", true, 15, now.minus(Duration.ofDays(1)));
        long untrusted = sensor("FC-UNTRUSTED", true, 15, now.minus(Duration.ofDays(1)));
        long inactive = sensor("FC-INACTIVE", false, 15, now.minus(Duration.ofDays(1)));
        long noReadings = sensor("FC-QUIET", true, 15, now.minus(Duration.ofDays(1)));
        for (long id : List.of(trusted, untrusted, inactive)) {
            readings(id, now.minus(Duration.ofMinutes(40)), now);
        }
        health(trusted, "HEALTHY", true, now);
        health(untrusted, "STUCK", false, now);
        health(inactive, "HEALTHY", true, now);
        health(noReadings, "HEALTHY", true, now);

        List<PublishedForecast> written = publisher.publishAll();

        assertThat(written).extracting(PublishedForecast::sensorId).contains(trusted)
                .doesNotContain(untrusted, inactive, noReadings);
        assertThat(published(untrusted)).isEmpty();
        assertThat(published(inactive)).isEmpty();
        assertThat(published(noReadings)).isEmpty();

        Map<String, Object> row = published(trusted).get(0);
        Forecast api = forecasts.forSensor(trusted, null).orElseThrow(); // what GET /api/v1/sensors/{id}/forecast says
        assertThat(row.get("method")).isEqualTo("ML"); // 40 minutes of history is enough for the model
        assertThat(number(row, "margin_15_c")).isCloseTo(api.margin15C(), within(0.01));
        assertThat(number(row, "margin_30_c")).isCloseTo(api.margin30C(), within(0.01));
        assertThat(number(row, "margin_60_c")).isCloseTo(api.margin60C(), within(0.01));
        assertThat(number(row, "warning_probability_30")).isCloseTo(api.risk30(), within(0.0001));
        assertThat(number(row, "warning_probability_60")).isCloseTo(api.risk60(), within(0.0001));
        // stored in UTC: computed_at is "now" by the database's own UTC clock, whatever the JVM's time zone
        assertThat(number(row, "age_s")).isBetween(-5.0, 60.0);
    }

    @Test
    void shortHistoryIsPublishedAsTrend() {
        Instant now = Instant.now();
        long id = sensor("FC-NEW", true, 15, now.minus(Duration.ofHours(1)));
        readings(id, now.minus(Duration.ofMinutes(5)), now);
        health(id, "HEALTHY", true, now);

        publisher.publishAll();

        assertThat(published(id)).singleElement().satisfies(r -> assertThat(r.get("method")).isEqualTo("TREND"));
    }

    @Test
    void rowIsUpdatedInPlaceAndRemovedWhenTheSensorStopsBeingTrusted() {
        Instant now = Instant.now();
        long id = sensor("FC-FLIP", true, 15, now.minus(Duration.ofDays(1)));
        readings(id, now.minus(Duration.ofMinutes(40)), now);
        health(id, "HEALTHY", true, now);

        publisher.publishAll();
        LocalDateTime firstComputedAt = (LocalDateTime) published(id).get(0).get("computed_at");
        publisher.publishAll();
        List<Map<String, Object>> again = published(id);
        assertThat(again).hasSize(1); // sensor_id is the key: one row per sensor, replaced each run
        assertThat((LocalDateTime) again.get(0).get("computed_at")).isAfterOrEqualTo(firstComputedAt);

        health(id, "STUCK", false, now);
        publisher.publishAll();
        assertThat(published(id)).isEmpty();
    }

    private List<Map<String, Object>> published(long sensorId) {
        return jdbc.queryForList("""
                SELECT sensor_id, margin_15_c, margin_30_c, margin_60_c, warning_probability_30, warning_probability_60,
                       method, predicted_condensation_at, computed_at,
                       TIMESTAMPDIFF(SECOND, computed_at, UTC_TIMESTAMP(3)) AS age_s
                FROM sensor_forecasts WHERE sensor_id = ?
                """, sensorId);
    }

    private static double number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).doubleValue();
    }
}
