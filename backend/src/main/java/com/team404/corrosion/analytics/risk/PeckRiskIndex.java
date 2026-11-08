package com.team404.corrosion.analytics.risk;

/**
 * Corrosion exposure based on Peck's temperature-humidity acceleration model:
 *
 * <pre>AF = (RH / RH_ref)^n * exp( (Ea / k) * (1/T_ref - 1/T) )     (T in Kelvin)</pre>
 *
 * AF says how many times faster damage builds up than at the reference condition. Damage is the sum of
 * AF * hours of exposure, and the risk score is damage / budget ({@code equipment_profile.risk_budget}).
 *
 * <p>Source of the constants: the defaults n = 2.66 (humidity exponent) and Ea = 0.79 eV (activation energy) are
 * the values usually attributed to D. S. Peck, "Comprehensive Model for Humidity Testing Correlation", Proc. IEEE
 * International Reliability Physics Symposium, 1986, which fitted them to aluminium corrosion in plastic-packaged
 * electronics. The reference condition (25 degrees C, 40 % RH) is this project's choice, not Peck's, and the budget
 * is a configurable demo value. The result is therefore a RELATIVE exposure index, not a prediction of time to
 * failure. All four numbers are stored per profile ({@code peck_n}, {@code peck_ea_ev}, {@code ref_temp_c},
 * {@code ref_rh_pct}), so a better-sourced set can replace them without code changes.
 */
public final class PeckRiskIndex {

    private static final double BOLTZMANN_EV_PER_K = 8.617333262e-5;
    private static final double KELVIN = 273.15;

    private final double n;
    private final double eaEv;
    private final double refTempK;
    private final double refRhPct;

    public PeckRiskIndex(double n, double eaEv, double refTempC, double refRhPct) {
        if (refRhPct <= 0) {
            throw new IllegalArgumentException("Reference RH must be positive!");
        }
        this.n = n;
        this.eaEv = eaEv;
        this.refRhPct = refRhPct;
        this.refTempK = refTempC + KELVIN;
    }

    /** How many times faster than the reference condition damage builds up at this temperature and humidity. */
    public double accelerationFactor(double tempC, double rhPct) {
        double tempK = tempC + KELVIN;
        // RH is floored at 1 % so a bad 0 % reading cannot zero out the whole product.
        double rhTerm = Math.pow(Math.max(rhPct, 1.0) / refRhPct, n);
        double tempTerm = Math.exp((eaEv / BOLTZMANN_EV_PER_K) * (1.0 / refTempK - 1.0 / tempK));
        return rhTerm * tempTerm;
    }

    /** Damage units added by one reading that represents {@code dtSeconds} of exposure. */
    public double damage(double tempC, double rhPct, double dtSeconds) {
        if (dtSeconds < 0) {
            throw new IllegalArgumentException("dtSeconds can't be negative");
        }
        return accelerationFactor(tempC, rhPct) * (dtSeconds / 3600.0);
    }

    /** Risk score: 0 = no exposure, 1.0 = the profile's budget is used up (it can exceed 1). */
    public static double score(double cumulativeDamage, double budget) {
        if (budget <= 0) {
            throw new IllegalArgumentException("budget needs to be positive");
        }
        return cumulativeDamage / budget;
    }
}
