package com.team404.corrosion.analytics.risk;

public final class PeckRiskIndex {
    private final double n;
    private final double eaEv;
    private final double refTempK;
    private final double refRhPct;
    private static final double boltzman_ev_per_k = 8.617333262e-5;
    private static final double kelvin = 273.15;

    public PeckRiskIndex (double n, double eaEv, double refTempC, double refRhPct){
        if(refRhPct <= 0){
            throw new IllegalArgumentException("Reference RH must be positive!");
        }
        this.n = n;
        this.eaEv=eaEv;
        this.refRhPct=refRhPct;
        this.refTempK=refTempC + kelvin;
    }

    public double accelarationFactor(double tempC, double rhPct){
        double tempK = tempC + kelvin;
        double rhTerm = Math.pow(Math.max(rhPct, 1.0) / refRhPct, n);
        double tempTerm = Math.exp((eaEv/boltzman_ev_per_k) * (1.0 / refTempK - 1.0 / tempK));
        return rhTerm * tempTerm;
    }

    public double damage(double tempC, double rhPct, double dtSeconds){
        if (dtSeconds< 0){
            throw new IllegalArgumentException("dtSeconds can't be negative");
        }
        return accelarationFactor(tempC, rhPct)*(dtSeconds/3600.0);
    }
    public static double score(double cumulativeDamage, double budget){
        if(budget<= 0){
            throw new IllegalArgumentException("budget needs to be positive");
        }
        return cumulativeDamage/budget;
    }
}
