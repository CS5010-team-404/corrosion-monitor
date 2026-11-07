package com.team404.corrosion.analytics.risk;

public final class DewPoint{
    private static final double x = 17.62;
    private static final double y = 243.12;

    private DewPoint() {
    }
        public static double dewPointC(double tempC, double rhPct){
            if (rhPct<=0 || rhPct>100) {
                throw new IllegalArgumentException("relative humidity must be between 0 to 100; with 100 inclusive");
            }
            double gamma = Math.log(rhPct/100.0) + (x*tempC)/(y+tempC);
            return (y*gamma)/(x-gamma);
        }
        public static double marginC (double surfaceTempC, double dewPointC){
            return surfaceTempC - dewPointC;
        }
}
