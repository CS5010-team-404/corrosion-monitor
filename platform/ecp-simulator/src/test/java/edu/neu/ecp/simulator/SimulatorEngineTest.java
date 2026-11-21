package edu.neu.ecp.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SimulatorEngineTest {

    private static SimulatedSensor sensor(String deviceId) {
        return new SimulatedSensor(deviceId, "secret", 22.0, 48.0, 1.5, 42L, 0);
    }

    @Test
    void changedIntervalTakesEffectWhileRunning() throws Exception {
        CountDownLatch threeSends = new CountDownLatch(3);
        AtomicInteger sent = new AtomicInteger();
        try (SimulatorEngine engine = new SimulatorEngine(r -> {
            sent.incrementAndGet();
            threeSends.countDown();
            return 202;
        }, Clock.systemUTC(), 1)) {
            // one reading an hour: with the jitter, nothing would be sent during this test
            engine.add(sensor("SIM-HOURLY"), Duration.ofHours(1));
            engine.add(sensor("SIM-HOURLY-2"), Duration.ofHours(1));
            assertEquals(3600, engine.status().get(0).intervalSeconds());

            assertTrue(engine.setInterval("SIM-HOURLY", Duration.ofMillis(50)));

            assertTrue(threeSends.await(5, TimeUnit.SECONDS), "expected readings at the new interval, got " + sent.get());
            assertEquals(0, engine.status().get(0).intervalSeconds()); // 50 ms rounds down to 0 s
            assertEquals(3600, engine.status().get(1).intervalSeconds()); // the other device is untouched
        }
    }

    @Test
    void unknownDeviceIsReported() {
        try (SimulatorEngine engine = new SimulatorEngine(r -> 202, Clock.systemUTC(), 1)) {
            assertFalse(engine.setInterval("NOPE", Duration.ofSeconds(30)));
        }
    }

    @Test
    void intervalMustBePositive() {
        try (SimulatorEngine engine = new SimulatorEngine(r -> 202, Clock.systemUTC(), 1)) {
            engine.add(sensor("SIM-001"), Duration.ofSeconds(15));
            assertThrows(IllegalArgumentException.class, () -> engine.setInterval("SIM-001", Duration.ZERO));
            assertThrows(IllegalArgumentException.class, () -> engine.add(sensor("SIM-002"), Duration.ofSeconds(-1)));
        }
    }

    @Test
    void sameIntervalIsANoOp() {
        try (SimulatorEngine engine = new SimulatorEngine(r -> 202, Clock.systemUTC(), 1)) {
            engine.add(sensor("SIM-001"), Duration.ofSeconds(15));
            assertTrue(engine.setInterval("SIM-001", Duration.ofSeconds(15)));
            assertEquals(15, engine.status().get(0).intervalSeconds());
        }
    }
}
