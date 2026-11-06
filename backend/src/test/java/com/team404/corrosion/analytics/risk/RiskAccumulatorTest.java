package com.team404.corrosion.analytics.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

public class RiskAccumulatorTest{
    private final PeckRiskIndex model = new PeckRiskIndex(2.66, 0.79, 25, 40);

    @Test
    void nonPositiveBudgetIsRejected(){
        assertThrows(IllegalArgumentException.class,()-> new RiskAccumulator(model, 0));
    }

    @Test
    void scoreIsDamageOverBudget(){
        RiskAccumulator acc =new RiskAccumulator(model, 10);
        acc.update(25, 40, 300);
        assertEquals(acc.cumulativeDamage()/10, acc.score(), 1e-12);
    }

    @Test
    void startsAtZero(){
        assertEquals(0.0,new RiskAccumulator(model, 100).score(),1e-12);
    }

    @Test
    void scoreGrowsWithEveryReading(){
        RiskAccumulator acc = new RiskAccumulator(model, 100);
        double first = acc.update(35, 75, 60);
        double second = acc.update(35, 75, 60);
        assertTrue(second > first);
    }

    @Test
    void longGapIsCappedSoAnOutageIsNotCountedAsExposure(){
        RiskAccumulator afterOutage = new RiskAccumulator(model, 100);
        afterOutage.update(25, 40,3*3600);

        RiskAccumulator capped = new RiskAccumulator(model, 100);
        capped.update(25, 40, RiskAccumulator.max_dt_sec);

        assertEquals(capped.cumulativeDamage(), afterOutage.cumulativeDamage(), 1e-12);
    }
}
