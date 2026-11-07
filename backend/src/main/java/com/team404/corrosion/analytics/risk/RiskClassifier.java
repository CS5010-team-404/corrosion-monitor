package com.team404.corrosion.analytics.risk;

public final class RiskClassifier{
    public record Thresholds(double marginWarnC, double marginCriticalC, double rhWarnPact, double rhCriticalPct){

    }
    private RiskClassifier(){

    }
    public static RiskLevel classify(Double marginC, Double rhPct, Thresholds t){
        if(marginC==null|| rhPct==null){
            return RiskLevel.UNKNOWN;
        }
        if(marginC<= t.marginCriticalC()||rhPct >= t.rhCriticalPct()){
            return RiskLevel.CRITICAL;
        }
        if(marginC<= t.marginWarnC()|| rhPct>= t.rhWarnPact()){
            return RiskLevel.WARNING;
        }
        return RiskLevel.NORMAL;
    }
}
