package com.team404.corrosion.analytics.signal;

import com.team404.corrosion.analytics.AnalyticsService;
import com.team404.corrosion.analytics.AssessmentRepo;
import com.team404.corrosion.analytics.EquipmentStatus;
import com.team404.corrosion.analytics.risk.RiskLevel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One pass of the analytics job (FR2 and FR3): evaluate every equipment item, store the evaluation in
 * risk_assessments, and write the condensation signals to alert_signals for the alert service to consume.
 *
 * <p>It is an ordinary bean, so tests call {@link #runOnce()} themselves. {@link AnalyticsScheduler} runs it on a
 * timer and is switched with ecp.analytics.signals.enabled.
 */
@Component
public class AnalyticsJob {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsJob.class);

    /** Assessments older than this are deleted on every run. */
    public static final Duration RETENTION = Duration.ofDays(7);

    static final String RISK_TYPE = "CONDENSATION_RISK";
    static final String FORECAST_TYPE = "CONDENSATION_FORECAST";

    /** What one pass did. */
    public record Result(int assessmentsStored, int raised, int cleared, int assessmentsPruned) {
    }

    private final AnalyticsService analytics;
    private final AssessmentRepo assessments;
    private final SignalStore signals;
    private final TransactionTemplate tx;
    private final Clock clock;

    @Autowired
    public AnalyticsJob(AnalyticsService analytics, AssessmentRepo assessments, SignalStore signals,
                        TransactionTemplate tx) {
        this(analytics, assessments, signals, tx, Clock.systemUTC());
    }

    public AnalyticsJob(AnalyticsService analytics, AssessmentRepo assessments, SignalStore signals,
                        TransactionTemplate tx, Clock clock) {
        this.analytics = analytics;
        this.assessments = assessments;
        this.signals = signals;
        this.tx = tx;
        this.clock = clock;
    }

    /** The scheduler's entry point: a failed pass is logged and retried on the next tick. */
    public Result run() {
        try {
            return runOnce();
        } catch (RuntimeException e) {
            log.warn("Analytics run failed, will retry: {}", e.toString());
            return new Result(0, 0, 0, 0);
        }
    }

    /** One pass. Everything it writes goes in one transaction, so a failure leaves no half-finished state. */
    public Result runOnce() {
        Instant now = clock.instant();
        List<AnalyticsService.Evaluation> evaluations = analytics.evaluateAll();
        Map<String, SignalStore.LastSignal> last = signals.lastSignals();
        Map<Long, Instant> lastMeasured = assessments.lastMeasuredAt();

        List<AssessmentRepo.Row> rows = new ArrayList<>();
        List<SignalStore.NewSignal> toWrite = new ArrayList<>();
        for (AnalyticsService.Evaluation evaluation : evaluations) {
            EquipmentStatus status = evaluation.status();
            assessmentRow(status, now, lastMeasured.get(status.equipmentId())).ifPresent(rows::add);
            riskSignal(status, last.get(riskKey(status.equipmentId())), now).ifPresent(toWrite::add);
            forecastSignal(status, evaluation.warningProbability60(), last.get(forecastKey(status.equipmentId())), now)
                    .ifPresent(toWrite::add);
        }

        int pruned = tx.execute(txStatus -> {
            assessments.insert(rows);
            toWrite.forEach(signals::append);
            return assessments.deleteBefore(now.minus(RETENTION));
        });
        int raised = (int) toWrite.stream().filter(s -> ConditionSignals.RAISED.equals(s.state())).count();
        return new Result(rows.size(), raised, toWrite.size() - raised, pruned);
    }

    static String riskKey(long equipmentId) {
        return "ANALYTICS:" + RISK_TYPE + ":" + equipmentId;
    }

    static String forecastKey(long equipmentId) {
        return "ANALYTICS:" + FORECAST_TYPE + ":" + equipmentId;
    }

    /** The evaluation to store, unless there is no trusted reading or this reading is already stored. */
    private static Optional<AssessmentRepo.Row> assessmentRow(EquipmentStatus s, Instant now, Instant lastStored) {
        if (s.riskLevel() == RiskLevel.UNKNOWN || s.updatedAt() == null) {
            return Optional.empty();
        }
        Instant measuredAt = Instant.parse(s.updatedAt());
        if (lastStored != null && !measuredAt.isAfter(lastStored)) {
            return Optional.empty();
        }
        return Optional.of(new AssessmentRepo.Row(s.equipmentId(), now, measuredAt, s.temperatureC(), s.surfaceTempC(),
                s.marginC(), s.dewPointC(), s.humidityPct(), s.riskIndex(), s.riskLevel().name()));
    }

    private static Optional<SignalStore.NewSignal> riskSignal(EquipmentStatus s, SignalStore.LastSignal last,
                                                              Instant now) {
        return ConditionSignals.forRisk(s.riskLevel(), last).map(d -> {
            boolean raised = ConditionSignals.RAISED.equals(d.state());
            String message = raised
                    ? String.format(Locale.ROOT, "Dew-point margin is %.1f °C (humidity %.0f %%, air %.1f °C, "
                            + "surface %.1f °C). %s level: warning at or below %.1f °C, critical at or below "
                            + "%.1f °C.", s.marginC(), s.humidityPct(), s.temperatureC(), s.surfaceTempC(),
                            capitalised(d.severity()), s.marginWarningC(), s.marginCriticalC())
                    : String.format(Locale.ROOT, "Dew-point margin is back to %.1f °C (humidity %.0f %%).",
                            s.marginC(), s.humidityPct());
            Map<String, Object> payload = payload(s);
            payload.put("level", s.riskLevel().name());
            payload.put("marginC", s.marginC());
            payload.put("dewPointC", s.dewPointC());
            payload.put("temperatureC", s.temperatureC());
            payload.put("surfaceTempC", s.surfaceTempC());
            payload.put("humidityPct", s.humidityPct());
            payload.put("riskIndex", s.riskIndex());
            payload.put("measuredAt", s.updatedAt());
            return new SignalStore.NewSignal(RISK_TYPE, d.severity(), d.state(), s.equipmentId(), s.sensorId(),
                    riskKey(s.equipmentId()), "Condensation risk: " + s.equipmentName(), message, payload, now);
        });
    }

    private static Optional<SignalStore.NewSignal> forecastSignal(EquipmentStatus s, Double probability60,
                                                                  SignalStore.LastSignal last, Instant now) {
        return ConditionSignals.forForecast(probability60, last).map(d -> {
            boolean raised = ConditionSignals.RAISED.equals(d.state());
            String message = raised
                    ? String.format(Locale.ROOT, "Condensation is forecast: %.0f %% probability that the margin falls to "
                            + "1 °C within 60 minutes (predicted margin %.1f °C in 60 minutes).%s",
                            100 * probability60, s.forecastMargin60C(), expectedAt(s))
                    : String.format(Locale.ROOT, "The condensation forecast has eased (%.0f %% probability within 60 "
                            + "minutes).", 100 * probability60);
            Map<String, Object> payload = payload(s);
            payload.put("warningProbability60", probability60);
            payload.put("forecastMargin15C", s.forecastMargin15C());
            payload.put("forecastMargin30C", s.forecastMargin30C());
            payload.put("forecastMargin60C", s.forecastMargin60C());
            payload.put("predictedCondensationAt", s.predictedCondensationAt());
            return new SignalStore.NewSignal(FORECAST_TYPE, d.severity(), d.state(), s.equipmentId(), s.sensorId(),
                    forecastKey(s.equipmentId()), "Condensation forecast: " + s.equipmentName(), message, payload, now);
        });
    }

    /** The alert service reads deviceId from the payload; the rest is context for the person reading the alert. */
    private static Map<String, Object> payload(EquipmentStatus s) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deviceId", s.deviceId());
        payload.put("equipmentName", s.equipmentName());
        payload.put("locationName", s.locationName());
        return payload;
    }

    private static String expectedAt(EquipmentStatus s) {
        return s.predictedCondensationAt() == null ? "" : " Condensation expected around " + s.predictedCondensationAt() + ".";
    }

    private static String capitalised(String severity) {
        return severity.charAt(0) + severity.substring(1).toLowerCase(Locale.ROOT);
    }
}
