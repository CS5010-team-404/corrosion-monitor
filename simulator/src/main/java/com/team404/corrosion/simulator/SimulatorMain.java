package com.team404.corrosion.simulator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class SimulatorMain {
    public static void main(String[] args) throws Exception {
        String sensorId = "6f1c2a9e-3b7d-4c1e-9a55-0d2f7c8e1b10";
        double speed = 720;                 // 720x: one simulated day in 2 real minutes
        double intervalSec = 30;            // simulated seconds between readings
        double durationH = 24;

        SensorSimulator sim = new SensorSimulator(sensorId, 42, 22.0, 12.0)
            // HVAC setback: temp drops 6 C over 2 h, holds 5 h, recovers over 2 h
            .addEvent(new ScenarioEvent(ScenarioEvent.Target.TEMP, 2.0, 2.0, 5.0, 2.0, -6.0))
            // sensor stuck from hour 15 to 16
            .addFault(new FaultWindow(FaultWindow.Type.STUCK, 15.0, 16.0, 0));

        Publisher pub = new Publisher("https://localhost:8443", sensorId, System.getenv("SENSOR_SECRET"));

        Instant simStart = Instant.parse("2026-11-03T00:00:00Z");
        long sleepMs = (long) (intervalSec * 1000 / speed);

        for (double sec = 0; sec < durationH * 3600; sec += intervalSec) {
            double h = sec / 3600.0;
            pub.networkDown = (h >= 8.0 && h < 8.5);          // 30 min outage to test buffering
            Instant simTime = simStart.plusSeconds((long) sec);
            sim.next(h, intervalSec, simTime).ifPresent(pub::publish);
            Thread.sleep(sleepMs);
        }
    }
}
