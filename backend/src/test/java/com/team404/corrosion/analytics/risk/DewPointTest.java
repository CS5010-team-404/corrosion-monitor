package com.team404.corrosion.analytics.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class DewPointTest{
    @Test
    void impossibleHumidityIsRejected(){
        assertThrows(IllegalArgumentException.class,()-> DewPoint.dewPointC(20, 0));
        assertThrows(IllegalArgumentException.class, ()-> DewPoint.dewPointC(20, 101));
    }


    @Test
    void atSaturationTheDewPointEqualsTheTemperature(){
        assertEquals(20.0, DewPoint.dewPointC(20, 100), 0.01);
    }

    @Test
    void matchesPsychrometricReferenceValues(){
        assertEquals(16.7, DewPoint.dewPointC(25, 60), 0.1);
        assertEquals(9.3, DewPoint.dewPointC(20, 50), 0.1);
    }

    @Test
    void marginIsSurfaceMinusDewPointAndNegativeWhenCondensing(){
        assertEquals(2.0, DewPoint.marginC(18.0, 16.0), 1e-9);
        assertTrue(DewPoint.marginC(15.0, 16.7) < 0);
    }
}