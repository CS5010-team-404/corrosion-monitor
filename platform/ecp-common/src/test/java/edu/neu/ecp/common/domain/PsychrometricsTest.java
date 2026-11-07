package edu.neu.ecp.common.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PsychrometricsTest {

    @Test
    void dewPointMatchesReferenceValuesWithinHalfADegree() {
        // Reference values from standard psychrometric tables.
        assertEquals(16.7, Psychrometrics.dewPointC(25.0, 60.0), 0.5);
        assertEquals(9.3, Psychrometrics.dewPointC(20.0, 50.0), 0.5);
        assertEquals(26.2, Psychrometrics.dewPointC(30.0, 80.0), 0.5);
        assertEquals(-6.8, Psychrometrics.dewPointC(10.0, 30.0), 0.5);
    }

    @Test
    void dewPointEqualsAirTemperatureAtSaturation() {
        assertEquals(21.0, Psychrometrics.dewPointC(21.0, 100.0), 1e-9);
    }

    @Test
    void rejectsImpossibleHumidity() {
        assertThrows(IllegalArgumentException.class, () -> Psychrometrics.dewPointC(20.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> Psychrometrics.dewPointC(20.0, 101.0));
    }

    @Test
    void marginUsesMeasuredSurfaceWhenPresentElseProfileOffset() {
        assertEquals(18.5, Psychrometrics.effectiveSurfaceTempC(18.5, 22.0, 3.0), 1e-9);
        assertEquals(19.0, Psychrometrics.effectiveSurfaceTempC(null, 22.0, 3.0), 1e-9);
        assertEquals(-0.5, Psychrometrics.marginC(15.0, 15.5), 1e-9);
    }

    @Test
    void timeOfWetnessFollowsIso9223() {
        assertTrue(Psychrometrics.isTimeOfWetness(5.0, 81.0));
        assertFalse(Psychrometrics.isTimeOfWetness(5.0, 80.0));
        assertFalse(Psychrometrics.isTimeOfWetness(-1.0, 95.0));
    }
}
