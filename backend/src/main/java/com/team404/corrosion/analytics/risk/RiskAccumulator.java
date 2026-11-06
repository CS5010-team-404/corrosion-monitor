package com.team404.corrosion.analytics.risk;

public final class RiskAccumulator{
    public static final double max_dt_sec=300;
    private final double budget;
    private final PeckRiskIndex model;
    public double cumulativeDamage;

    public RiskAccumulator(PeckRiskIndex model, double budget){
        if(budget <= 0){
            throw new IllegalArgumentException("Budget should be positive!");
        }
        this.model = model;
        this.budget = budget;
    }

    public double update(double tempC, double rhPct, double dtSeconds){
        double cappedDt = Math.min(dtSeconds, max_dt_sec);
        cumulativeDamage += model.damage(tempC, rhPct, cappedDt);
        return score();
    }

    public double score(){
        return PeckRiskIndex.score(cumulativeDamage, budget);
    }

    public double cumulativeDamage(){
        return cumulativeDamage;
    }
}
