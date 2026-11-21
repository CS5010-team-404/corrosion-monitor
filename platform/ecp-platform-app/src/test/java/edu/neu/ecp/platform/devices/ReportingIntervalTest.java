package edu.neu.ecp.platform.devices;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReportingIntervalTest {

    @Test
    void acceptsFiveSecondsToOneHour() {
        assertTrue(ReportingInterval.problem(5).isEmpty());
        assertTrue(ReportingInterval.problem(15).isEmpty());
        assertTrue(ReportingInterval.problem(3600).isEmpty());
    }

    @Test
    void rejectsOutOfRangeAndMissing() {
        assertEquals("expectedIntervalS must be between 5 and 3600 seconds", ReportingInterval.problem(4).orElseThrow());
        assertTrue(ReportingInterval.problem(3601).isPresent());
        assertTrue(ReportingInterval.problem(0).isPresent());
        assertTrue(ReportingInterval.problem(-15).isPresent());
        assertEquals("expectedIntervalS is required", ReportingInterval.problem(null).orElseThrow());
    }
}
