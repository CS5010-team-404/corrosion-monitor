package edu.neu.ecp.health.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * Every threshold the health rules use, in one immutable object. Defaults suit a sensor that reports every
 * 10-15 seconds (proposal) with SHT31-class physical limits. Override in application.yml under {@code ecp.health}.
 *
 * @param lateFactor              connectivity is LATE after this many expected intervals without a reading
 * @param offlineFactor           ... and OFFLINE after this many
 * @param window                  how much recent history each evaluation looks at
 * @param stuckMinReadings        consecutive identical readings that count as stuck
 * @param stuckEpsilon            values within this distance count as "identical" (0 = exactly equal)
 * @param tempMinC                lowest temperature the sensor can physically report
 * @param tempMaxC                highest temperature the sensor can physically report
 * @param humidityMinPct          lowest RH the sensor can physically report
 * @param humidityMaxPct          highest RH the sensor can physically report
 * @param outOfRangeMinCount      impossible readings in the window before OUT_OF_RANGE opens
 * @param spikeTempC              temperature jump between consecutive readings that counts as a spike
 * @param spikeHumidityPct        humidity jump between consecutive readings that counts as a spike
 * @param erraticMinSpikes        spikes in the window before ERRATIC opens
 * @param lossyMissingPct         missing readings (by sequence number) before LOSSY opens
 * @param lossyMinExpected        do not judge loss on fewer expected readings than this
 * @param signatureFailureMin     rejected signatures in the window before SIGNATURE_FAILURES opens
 * @param clockSkewThreshold      median |received - measured| before CLOCK_SKEW opens
 * @param recoveryEvaluations     clean evaluations in a row needed to close an issue (hysteresis)
 */
public record HealthPolicy(
        double lateFactor,
        double offlineFactor,
        Duration window,
        int stuckMinReadings,
        double stuckEpsilon,
        double tempMinC,
        double tempMaxC,
        double humidityMinPct,
        double humidityMaxPct,
        int outOfRangeMinCount,
        double spikeTempC,
        double spikeHumidityPct,
        int erraticMinSpikes,
        double lossyMissingPct,
        int lossyMinExpected,
        int signatureFailureMin,
        Duration clockSkewThreshold,
        int recoveryEvaluations) {

    public HealthPolicy {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(clockSkewThreshold, "clockSkewThreshold");
        if (lateFactor <= 0 || offlineFactor <= lateFactor) {
            throw new IllegalArgumentException("need 0 < lateFactor < offlineFactor");
        }
        if (stuckMinReadings < 3) {
            throw new IllegalArgumentException("stuckMinReadings must be at least 3");
        }
        if (recoveryEvaluations < 1) {
            throw new IllegalArgumentException("recoveryEvaluations must be at least 1");
        }
    }

    public static HealthPolicy defaults() {
        return new HealthPolicy(
                2.0, 6.0, Duration.ofMinutes(15),
                20, 0.0,
                -40.0, 125.0, 0.0, 100.0, 2,
                5.0, 20.0, 3,
                25.0, 8,
                3,
                Duration.ofMinutes(2),
                3);
    }
}
