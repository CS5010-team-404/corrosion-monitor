package edu.neu.ecp.health.infra;

import edu.neu.ecp.health.domain.HealthPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code ecp.health.*} from application.yml. Defaults match {@link HealthPolicy#defaults()}. */
@ConfigurationProperties(prefix = "ecp.health")
public class HealthProperties {

    private final HealthPolicy d = HealthPolicy.defaults();

    private Duration evaluateEvery = Duration.ofSeconds(5);
    private double lateFactor = d.lateFactor();
    private double offlineFactor = d.offlineFactor();
    private Duration window = d.window();
    private int stuckMinReadings = d.stuckMinReadings();
    private double stuckEpsilon = d.stuckEpsilon();
    private double tempMinC = d.tempMinC();
    private double tempMaxC = d.tempMaxC();
    private double humidityMinPct = d.humidityMinPct();
    private double humidityMaxPct = d.humidityMaxPct();
    private int outOfRangeMinCount = d.outOfRangeMinCount();
    private double spikeTempC = d.spikeTempC();
    private double spikeHumidityPct = d.spikeHumidityPct();
    private int erraticMinSpikes = d.erraticMinSpikes();
    private double lossyMissingPct = d.lossyMissingPct();
    private int lossyMinExpected = d.lossyMinExpected();
    private int signatureFailureMin = d.signatureFailureMin();
    private Duration clockSkewThreshold = d.clockSkewThreshold();
    private int recoveryEvaluations = d.recoveryEvaluations();
    /** A background service whose last success is older than this is reported DOWN. */
    private Duration serviceStaleAfter = Duration.ofMinutes(2);

    public HealthPolicy toPolicy() {
        return new HealthPolicy(lateFactor, offlineFactor, window, stuckMinReadings, stuckEpsilon, tempMinC, tempMaxC,
                humidityMinPct, humidityMaxPct, outOfRangeMinCount, spikeTempC, spikeHumidityPct, erraticMinSpikes,
                lossyMissingPct, lossyMinExpected, signatureFailureMin, clockSkewThreshold, recoveryEvaluations);
    }

    // --- getters and setters (required for binding) -------------------------------------------------
    public Duration getEvaluateEvery() { return evaluateEvery; }
    public void setEvaluateEvery(Duration v) { evaluateEvery = v; }
    public double getLateFactor() { return lateFactor; }
    public void setLateFactor(double v) { lateFactor = v; }
    public double getOfflineFactor() { return offlineFactor; }
    public void setOfflineFactor(double v) { offlineFactor = v; }
    public Duration getWindow() { return window; }
    public void setWindow(Duration v) { window = v; }
    public int getStuckMinReadings() { return stuckMinReadings; }
    public void setStuckMinReadings(int v) { stuckMinReadings = v; }
    public double getStuckEpsilon() { return stuckEpsilon; }
    public void setStuckEpsilon(double v) { stuckEpsilon = v; }
    public double getTempMinC() { return tempMinC; }
    public void setTempMinC(double v) { tempMinC = v; }
    public double getTempMaxC() { return tempMaxC; }
    public void setTempMaxC(double v) { tempMaxC = v; }
    public double getHumidityMinPct() { return humidityMinPct; }
    public void setHumidityMinPct(double v) { humidityMinPct = v; }
    public double getHumidityMaxPct() { return humidityMaxPct; }
    public void setHumidityMaxPct(double v) { humidityMaxPct = v; }
    public int getOutOfRangeMinCount() { return outOfRangeMinCount; }
    public void setOutOfRangeMinCount(int v) { outOfRangeMinCount = v; }
    public double getSpikeTempC() { return spikeTempC; }
    public void setSpikeTempC(double v) { spikeTempC = v; }
    public double getSpikeHumidityPct() { return spikeHumidityPct; }
    public void setSpikeHumidityPct(double v) { spikeHumidityPct = v; }
    public int getErraticMinSpikes() { return erraticMinSpikes; }
    public void setErraticMinSpikes(int v) { erraticMinSpikes = v; }
    public double getLossyMissingPct() { return lossyMissingPct; }
    public void setLossyMissingPct(double v) { lossyMissingPct = v; }
    public int getLossyMinExpected() { return lossyMinExpected; }
    public void setLossyMinExpected(int v) { lossyMinExpected = v; }
    public int getSignatureFailureMin() { return signatureFailureMin; }
    public void setSignatureFailureMin(int v) { signatureFailureMin = v; }
    public Duration getClockSkewThreshold() { return clockSkewThreshold; }
    public void setClockSkewThreshold(Duration v) { clockSkewThreshold = v; }
    public int getRecoveryEvaluations() { return recoveryEvaluations; }
    public void setRecoveryEvaluations(int v) { recoveryEvaluations = v; }
    public Duration getServiceStaleAfter() { return serviceStaleAfter; }
    public void setServiceStaleAfter(Duration v) { serviceStaleAfter = v; }
}
