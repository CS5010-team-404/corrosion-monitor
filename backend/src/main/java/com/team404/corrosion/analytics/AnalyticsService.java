package com.team404.corrosion.analytics;

import com.team404.corrosion.alert.calculation.DewPoint;
import com.team404.corrosion.analytics.risk.PeckRiskIndex;
import com.team404.corrosion.analytics.risk.RiskAccumulator;
import com.team404.corrosion.analytics.risk.RiskClassifier;
import com.team404.corrosion.analytics.risk.RiskLevel;
import com.team404.corrosion.analytics.risk.TimeOfWetness;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Turns the trusted readings of each equipment item into the status the dashboard shows: dew point, condensation
 * margin, corrosion risk index, time of wetness, the platform's condensation forecast and a NORMAL / WARNING /
 * CRITICAL / UNKNOWN level. It also serves the stored assessments (the Equipment page chart) and the risk figures
 * of a period (the reports).
 *
 * <p>The dew point formula is the team's shared {@link DewPoint} (Magnus, 17.62 and 243.12 degrees C). The forecast
 * is read from the platform's sensor_forecasts table and ignored once it is older than five minutes.
 */
@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    /** How far back readings are used for the risk index and time of wetness. */
    static final Duration WINDOW = Duration.ofHours(24);

    /** A forecast row older than this is ignored: the platform's publisher has stopped, or the sensor stopped qualifying. */
    static final Duration FORECAST_MAX_AGE = Duration.ofMinutes(5);

    /** The assessments are kept for seven days, so a longer request is cut to that. */
    public static final int MAX_ASSESSMENT_MINUTES = 7 * 24 * 60;

    /**
     * One equipment item's status, with the one value the signal job needs that the status type does not carry.
     *
     * @param warningProbability60 the platform's 60 minute warning probability from a fresh forecast, or null when
     *                             there is no fresh forecast
     */
    public record Evaluation(EquipmentStatus status, Double warningProbability60) {
    }

    private final AnalyticsRepo repository;
    private final AssessmentRepo assessments;
    private final Clock clock;

    @Autowired
    public AnalyticsService(AnalyticsRepo repository, AssessmentRepo assessments) {
        this(repository, assessments, Clock.systemUTC());
    }

    /** With a given clock, so tests (here and in the job's package) control what "now" is. */
    public AnalyticsService(AnalyticsRepo repository, AssessmentRepo assessments, Clock clock) {
        this.repository = repository;
        this.assessments = assessments;
        this.clock = clock;
    }

    public List<EquipmentStatus> equipmentStatuses() {
        return evaluateAll().stream().map(Evaluation::status).toList();
    }

    /**
     * Every equipment item's status. An item that cannot be assessed (bad data in its profile, say) is logged and
     * reported as UNKNOWN, so one broken item never takes the others down with it.
     */
    public List<Evaluation> evaluateAll() {
        Instant now = clock.instant();
        return repository.loadSnapshots(now.minus(WINDOW)).stream().map(s -> evaluate(s, now)).toList();
    }

    /**
     * The stored assessments of one item from the last {@code minutes} minutes (between 1 and seven days), oldest
     * first, or empty if there is no such equipment item.
     */
    public Optional<List<AssessmentPoint>> assessments(long equipmentId, int minutes) {
        if (!assessments.equipmentExists(equipmentId)) {
            return Optional.empty();
        }
        int clamped = Math.max(1, Math.min(minutes, MAX_ASSESSMENT_MINUTES));
        return Optional.of(assessments.points(equipmentId, clock.instant().minus(Duration.ofMinutes(clamped))));
    }

    /** The risk figures of every equipment item that was assessed in [from, to); null means open on that side. */
    public List<EquipmentRiskSummary> riskSummary(Instant from, Instant to) {
        return riskSummary(from, to, null);
    }

    /**
     * The risk figures per equipment item for the reports: time in each level, peak risk index and wet hours. The
     * location is its name, or null for all locations.
     */
    public List<EquipmentRiskSummary> riskSummary(Instant from, Instant to, String location) {
        Instant now = clock.instant();
        Instant end = to == null || to.isAfter(now) ? now : to;
        return RiskSummaryCalculator.summarize(assessments.summaryRows(from, to, location), end);
    }

    private static Evaluation evaluate(EquipmentSnap s, Instant now) {
        try {
            return assess(s, now);
        } catch (RuntimeException e) {
            log.warn("Could not assess equipment {}: {}", s.equipmentId(), e.toString());
            return new Evaluation(unknown(s), null);
        }
    }

    private static Evaluation assess(EquipmentSnap s, Instant now) {
        List<Reading> readings = s.readings();
        if (readings.isEmpty()) {
            return new Evaluation(unknown(s), null);
        }
        Reading latest = readings.get(readings.size() - 1);
        if (latest.humidityPct() <= 0 || latest.humidityPct() > 100) {
            return new Evaluation(unknown(s), null);   // impossible humidity: do not trust it
        }

        double surfaceTempC = latest.surfaceTempC() != null
                ? latest.surfaceTempC()
                : latest.temperatureC() - s.surfaceOffsetC();
        double dewPointC = DewPoint.celsius(latest.temperatureC(), latest.humidityPct());
        double marginC = DewPoint.margin(surfaceTempC, latest.temperatureC(), latest.humidityPct());
        RiskLevel level = RiskClassifier.classify(marginC, latest.humidityPct(), s.thresholds());
        SensorForecast forecast = freshForecast(s, now);

        EquipmentStatus status = new EquipmentStatus(
                s.equipmentId(), s.equipmentName(), s.locationName(), s.profileName(), s.profileKind(),
                level, round(riskIndex(s, readings), 4), round(latest.temperatureC(), 2),
                round(latest.humidityPct(), 2), round(surfaceTempC, 2), round(dewPointC, 2), round(marginC, 2),
                forecast == null ? null : round(forecast.margin15C(), 2),
                forecast == null ? null : round(forecast.margin30C(), 2),
                forecast == null ? null : round(forecast.margin60C(), 2),
                forecast == null || forecast.predictedCondensationAt() == null
                        ? null : forecast.predictedCondensationAt().toString(),
                latest.measuredAt().toString(), s.thresholds().marginWarnC(), s.thresholds().marginCriticalC(),
                s.thresholds().rhWarnPct(), s.thresholds().rhCriticalPct(),
                s.tempMinC(), s.tempMaxC(), s.surfaceOffsetC(),
                s.sensorId(), s.deviceId(), s.trusted(), round(timeOfWetnessMinutes(readings), 1));
        return new Evaluation(status, forecast == null ? null : forecast.warningProbability60());
    }

    /** The sensor's forecast if the platform computed it at most five minutes ago. */
    private static SensorForecast freshForecast(EquipmentSnap s, Instant now) {
        SensorForecast forecast = s.forecast();
        if (forecast == null || forecast.computedAt().isBefore(now.minus(FORECAST_MAX_AGE))) {
            return null;
        }
        return forecast;
    }

    /** Peck damage over every reading in the window, divided by the profile's budget. */
    private static double riskIndex(EquipmentSnap s, List<Reading> readings) {
        PeckRiskIndex model = new PeckRiskIndex(s.peckN(), s.peckEaEv(), s.refTempC(), s.refRhPct());
        RiskAccumulator accumulator = new RiskAccumulator(model, s.riskBudget());
        Instant previous = null;
        for (Reading r : readings) {
            double dtSeconds = previous == null ? 0 : Math.max(0, Duration.between(previous, r.measuredAt()).toSeconds());
            accumulator.update(r.temperatureC(), r.humidityPct(), dtSeconds);
            previous = r.measuredAt();
        }
        return accumulator.score();
    }

    private static double timeOfWetnessMinutes(List<Reading> readings) {
        List<TimeOfWetness.Sample> samples = readings.stream()
                .map(r -> new TimeOfWetness.Sample(r.measuredAt(), r.temperatureC(), r.humidityPct()))
                .toList();
        return TimeOfWetness.wetMinutes(samples, Duration.ofSeconds((long) RiskAccumulator.MAX_DT_SECONDS));
    }

    /** No trusted reading: say UNKNOWN instead of pretending the equipment is fine. */
    private static EquipmentStatus unknown(EquipmentSnap s) {
        return new EquipmentStatus(
                s.equipmentId(), s.equipmentName(), s.locationName(), s.profileName(), s.profileKind(),
                RiskLevel.UNKNOWN, null, null, null, null, null, null, null, null, null, null, null,
                s.thresholds().marginWarnC(), s.thresholds().marginCriticalC(),
                s.thresholds().rhWarnPct(), s.thresholds().rhCriticalPct(),
                s.tempMinC(), s.tempMaxC(), s.surfaceOffsetC(),
                s.sensorId(), s.deviceId(), s.trusted(), null);
    }

    private static double round(double value, int places) {
        double factor = Math.pow(10, places);
        return Math.round(value * factor) / factor;
    }
}
