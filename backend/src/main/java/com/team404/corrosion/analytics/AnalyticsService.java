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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Turns the trusted readings of each equipment item into the status the dashboard shows: dew point, condensation
 * margin, corrosion risk index, time of wetness and a NORMAL / WARNING / CRITICAL / UNKNOWN level.
 *
 * <p>The dew point formula is the team's shared {@link DewPoint} (Magnus, 17.62 and 243.12 degrees C).
 * Not done yet: the 15, 30 and 60 minute forecast fields and predictedCondensationAt stay null.
 */
@Service
public class AnalyticsService {

    /** How far back readings are used for the risk index and time of wetness. */
    static final Duration WINDOW = Duration.ofHours(24);

    private final AnalyticsRepo repository;
    private final Clock clock;

    @Autowired
    public AnalyticsService(AnalyticsRepo repository) {
        this(repository, Clock.systemUTC());
    }

    AnalyticsService(AnalyticsRepo repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public List<EquipmentStatus> equipmentStatuses() {
        Instant since = clock.instant().minus(WINDOW);
        return repository.loadSnapshots(since).stream().map(AnalyticsService::assess).toList();
    }

    private static EquipmentStatus assess(EquipmentSnap s) {
        List<Reading> readings = s.readings();
        if (readings.isEmpty()) {
            return unknown(s);
        }
        Reading latest = readings.get(readings.size() - 1);
        if (latest.humidityPct() <= 0 || latest.humidityPct() > 100) {
            return unknown(s);   // impossible humidity: do not trust it
        }

        double surfaceTempC = latest.surfaceTempC() != null
                ? latest.surfaceTempC()
                : latest.temperatureC() - s.surfaceOffsetC();
        double dewPointC = DewPoint.celsius(latest.temperatureC(), latest.humidityPct());
        double marginC = DewPoint.margin(surfaceTempC, latest.temperatureC(), latest.humidityPct());
        RiskLevel level = RiskClassifier.classify(marginC, latest.humidityPct(), s.thresholds());

        return new EquipmentStatus(
                s.equipmentId(), s.equipmentName(), s.locationName(), s.profileName(), s.profileKind(),
                level, round(riskIndex(s, readings), 4), round(latest.temperatureC(), 2),
                round(latest.humidityPct(), 2), round(surfaceTempC, 2), round(dewPointC, 2), round(marginC, 2),
                null, null, null, null,
                latest.measuredAt().toString(), s.thresholds().marginWarnC(), s.thresholds().marginCriticalC(),
                s.thresholds().rhWarnPct(), s.thresholds().rhCriticalPct(),
                s.tempMinC(), s.tempMaxC(), s.surfaceOffsetC(),
                s.sensorId(), s.deviceId(), s.trusted(), round(timeOfWetnessMinutes(readings), 1));
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
