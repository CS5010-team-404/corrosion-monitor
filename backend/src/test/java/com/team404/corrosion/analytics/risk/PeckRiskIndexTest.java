package com.team404.corrosion.analytics.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class PeckRiskIndexTest{
    private final PeckRiskIndex model = new PeckRiskIndex(2.66,0.79, 25,40);

    @Test
    void negativeDurationIsRejected(){
        assertThrows(IllegalArgumentException.class, ()-> model.damage(25,40, -1));
    }

    @Test
    void nonPositiveBudgetIsRejected(){
        assertThrows(IllegalArgumentException.class, ()-> PeckRiskIndex.score(10, 0));
    }

    @Test
    void nonPositiveReferenceHumidityIsRejected(){
        assertThrows(IllegalArgumentException.class, ()-> new PeckRiskIndex(2.66, 0.79, 25, 0));
    }

    @Test
    void scoreIsDamageOverBudget() {
        assertEquals(0.25, PeckRiskIndex.score(25, 100), 1e-9);
        assertEquals(1.5, PeckRiskIndex.score(150, 100), 1e-9);
    }

    @Test
    void zeroHumidityReadingDoesNotZeroTheFactor(){
        assertTrue(model.accelarationFactor(25, 0)>0);
    }

    @Test
    void damageIsAccelerationFactorTimesHours(){
        assertEquals(2.0, model.damage(25,40,7200),1e-9);
    }

    @Test
    void humidityTermFollowsThePowerLaw(){
        assertEquals(Math.pow(2.0,2.66), model.accelarationFactor(25, 80), 1e-9);
    }

    @Test
    void accelerationFactorIsOneAtTheReferenceCondition(){
        assertEquals(1.0, model.accelarationFactor(25, 40), 1e-9);
    }

    @Test
    void humidAndWarmDamagesFasterThanDryAndCool(){
        assertTrue(model.accelarationFactor(30, 80)>model.accelarationFactor(20, 40));
    }

    @Test
    void higherHumidityAloneRaisesTheFactor(){
        assertTrue(model.accelarationFactor(25, 70)>model.accelarationFactor(25, 50));
    }

    @Test
    void higherTemperatureAloneRaisesTheFactor(){
        assertTrue(model.accelarationFactor(35, 40) > model.accelarationFactor(25, 40));
    }
}
