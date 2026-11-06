package com.team404.corrosion.analytics.risk;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class TimeOfWetness {
    public static final double rh_threshold_pct= 80.0;
    public static final double temp_threshold_c= 0.0;

    public record sample(Instant at, double tempC, double rhPct){

    }

    private TimeOfWetness(){

    }

    public static boolean isWet(double tempC, double rhPct){
        return rhPct>rh_threshold_pct && tempC>temp_threshold_c;
    }

    public static double wetMinutes(List<sample> samples, Duration maxGap){
        double wetSeconds= 0;
        for(int i= 0; i<samples.size()-1; i++) {
            sample current = samples.get(i);
            if (!isWet(current.tempC(), current.rhPct())) {
                continue;
            }
            Duration gap = Duration.between(current.at(), samples.get(i + 1).at());
            if (gap.isNegative()) {
                throw new IllegalArgumentException("samples must be sorted with oldest going first");
            }
            wetSeconds = wetSeconds+ Math.min(gap.getSeconds(), maxGap.getSeconds());
        }
        return wetSeconds/60.0;
    }
}
