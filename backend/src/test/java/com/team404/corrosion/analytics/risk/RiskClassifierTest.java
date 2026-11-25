package com.team404.corrosion.analytics.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.team404.corrosion.analytics.risk.RiskClassifier.Thresholds;
import org.junit.jupiter.api.Test;

class RiskClassifierTest{
    private static final Thresholds IT = new Thresholds(3.0, 1.0, 60.0, 70.0);

    @Test
    void normalWhenMarginAndHumidityAreComfortable(){
        assertEquals(RiskLevel.NORMAL, RiskClassifier.classify(8.0, 45.0, IT));
    }

    @Test
    void warningWhenMarginIsAtOrBelowTheWarningThreshold(){
        assertEquals(RiskLevel.WARNING, RiskClassifier.classify(3.0, 45.0, IT));
        assertEquals(RiskLevel.WARNING, RiskClassifier.classify(1.8, 45.0, IT));
    }

    @Test
    void criticalWhenMarginIsAtOrBelowTheCriticalThreshold(){
        assertEquals(RiskLevel.CRITICAL, RiskClassifier.classify(1.0, 45.0, IT));
        assertEquals(RiskLevel.CRITICAL, RiskClassifier.classify(-0.5, 45.0, IT));
    }

    @Test
    void humidityAloneCanRaiseTheLevel(){
        assertEquals(RiskLevel.WARNING, RiskClassifier.classify(8.0, 60.0, IT));
        assertEquals(RiskLevel.CRITICAL, RiskClassifier.classify(8.0, 70.0, IT));
    }

    @Test
    void theWorseOfMarginAndHumidityWins(){
        assertEquals(RiskLevel.CRITICAL, RiskClassifier.classify(2.0, 75.0, IT));
    }

    @Test
    void unknownWhenThereIsNoTrustedReading(){
        assertEquals(RiskLevel.UNKNOWN, RiskClassifier.classify(null, null, IT));
        assertEquals(RiskLevel.UNKNOWN, RiskClassifier.classify(5.0, null, IT));
    }
}