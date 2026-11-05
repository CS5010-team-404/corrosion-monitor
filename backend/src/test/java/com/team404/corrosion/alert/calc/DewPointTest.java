package com.team404.corrosion.alert.calc;

import com.team404.corrosion.alert.calculation.DewPoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DewPointTest {

    /** Reference dew points are rounded to 0.1 C, so allow a small tolerance. */
    private static final double REFERENCE_TOLERANCE_C = 0.1;

    /** Mathematically exact cases (such as 100% humidity) need a much tighter tolerance. */
    private static final double EXACT_TOLERANCE_C = 0.001;

    @Test
    void dewPointMatchesPublishedReferenceValues() {
        assertEquals(16.7, DewPoint.celsius(25, 60), REFERENCE_TOLERANCE_C);
        assertEquals(18.4, DewPoint.celsius(30, 50), REFERENCE_TOLERANCE_C);
        assertEquals(-6.8, DewPoint.celsius(10, 30), REFERENCE_TOLERANCE_C);
    }

    @Test
    void dewPointEqualsAirTemperatureAtFullHumidity() {
        double airTemperatureC = 20;
        double dewPointC = DewPoint.celsius(airTemperatureC, 100);

        assertEquals(airTemperatureC, dewPointC, EXACT_TOLERANCE_C);
    }

    @Test
    void marginIsSurfaceTemperatureMinusDewPoint() {
        double marginC = DewPoint.margin(25, 25, 60);

        assertEquals(8.3, marginC, REFERENCE_TOLERANCE_C);
    }

    @Test
    void marginIsZeroWhenSurfaceIsAtDewPoint() {
        double marginC = DewPoint.margin(20, 20, 100);

        assertEquals(0.0, marginC, EXACT_TOLERANCE_C);
    }

    @Test
    void marginShrinksByTheSameAmountThatTheSurfaceCools() {
        double warmSurfaceMarginC = DewPoint.margin(25, 25, 60);
        double coolerSurfaceMarginC = DewPoint.margin(20, 25, 60);

        assertEquals(5.0, warmSurfaceMarginC - coolerSurfaceMarginC, EXACT_TOLERANCE_C);
    }

    @Test
    void rejectsZeroHumidity() {
        assertThrows(IllegalArgumentException.class, () -> DewPoint.celsius(20, 0));
    }

    @Test
    void rejectsHumidityAboveOneHundredPercent() {
        assertThrows(IllegalArgumentException.class, () -> DewPoint.celsius(20, 100.5));
    }

    @Test
    void rejectsNotANumberInputs() {
        assertThrows(IllegalArgumentException.class, () -> DewPoint.celsius(Double.NaN, 50));
        assertThrows(IllegalArgumentException.class, () -> DewPoint.celsius(20, Double.NaN));
    }
}
