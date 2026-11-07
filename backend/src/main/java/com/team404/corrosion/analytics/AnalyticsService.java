package com.team404.corrosion.analytics;

import com.team404.corrosion.analytics.risk.DewPoint;
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

@Service
public class AnalyticsService{
    static final Duration window = Duration.ofHours(24);
    private final AnalyticsRepo repository;
    private final Clock clock;

    @Autowired
    public AnalyticsService(AnalyticsRepo repository){
        this(repository, Clock.systemUTC());
    }
    AnalyticsService(AnalyticsRepo repository, Clock clock){
        this.repository= repository;
        this.clock = clock;
    }
    public List<EquipmentStatus> equipmentStatuses(){
        Instant since = clock.instant().minus(window);
        return repository.loadSnapshots(since).stream().map(AnalyticsService::assess).toList();
    }

    private static EquipmentStatus assess(EquipmentSnap s) {
        ProfileExtras extras = extrasFor(s.profileId());
        List<Reading> readings = s.readings();
        if (readings.isEmpty()) {
            return unknown(s, extras);
        }
        Reading latest = readings.get(readings.size() - 1);
        if (latest.humidityPct() <= 0 || latest.humidityPct() > 100) {
            return unknown(s, extras);
        }
        double surfaceTempC = latest.surfaceTempC() != null ? latest.surfaceTempC() : latest.temperatureC() - extras.surfaceOffsetC();
        double dewPointC = DewPoint.dewPointC(latest.temperatureC(), latest.humidityPct());
        double marginC = DewPoint.marginC(surfaceTempC, dewPointC);
        RiskLevel level = RiskClassifier.classify(marginC, latest.humidityPct(), s.thresholds());

        return new EquipmentStatus(
                s.equipmentId(), s.equipmentName(), s.locationName(), s.profileName(), extras.kind(),
                level, round(riskIndex(s, readings), 4), round(latest.temperatureC(), 2), round(latest.humidityPct(), 2), round(surfaceTempC, 2),
                round(dewPointC, 2), round(marginC, 2), null, null, null, null,
                latest.measuredAt().toString(), s.thresholds().marginWarnC(), s.thresholds().marginCriticalC(),
                s.thresholds().rhWarnPact(), s.thresholds().rhCriticalPct(), extras.tempMinC(), extras.tempMaxC(), extras.surfaceOffsetC(),
                s.sensorId(), s.deviceId(), s.trusted(), round(timeOfWetnessMinutes(readings), 1));
    }

    private static double riskIndex(EquipmentSnap s, List<Reading> readings){
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
        List<TimeOfWetness.sample> samples = readings.stream().map(r -> new TimeOfWetness.sample(r.measuredAt(), r.temperatureC(), r.humidityPct())).toList();
        return TimeOfWetness.wetMinutes(samples, Duration.ofSeconds((long) RiskAccumulator.max_dt_sec));
    }

    private static EquipmentStatus unknown(EquipmentSnap s, ProfileExtras extras) {
        return new EquipmentStatus(
                s.equipmentId(), s.equipmentName(), s.locationName(), s.profileName(), extras.kind(),
                RiskLevel.UNKNOWN, null, null, null, null, null, null, null, null, null, null, null,
                s.thresholds().marginWarnC(), s.thresholds().marginCriticalC(), s.thresholds().rhWarnPact(), s.thresholds().rhCriticalPct(),
                extras.tempMinC(), extras.tempMaxC(), extras.surfaceOffsetC(), s.sensorId(), s.deviceId(), s.trusted(), null);
    }

    private record ProfileExtras(String kind, double tempMinC, double tempMaxC, double surfaceOffsetC) {
    }

    private static ProfileExtras extrasFor(long profileId){
        return switch ((int) profileId){
            case 2 -> new ProfileExtras("tel", 5, 40, 1.5);
            case 3 -> new ProfileExtras("plc", 0, 55, 1.5);
            case 4 -> new ProfileExtras("out", -20, 50, 1.5);
            default -> new ProfileExtras("it", 18, 27, 1.5);
        };
    }

    private static double round(double value, int places){
        double factor = Math.pow(10, places);
        return Math.round(value * factor) / factor;
    }
}