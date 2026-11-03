package edu.neu.ecp.simulator;

import java.time.Clock;
import java.time.Duration;

/**
 * Standalone simulator (the app also embeds one; see EmbeddedSimulator). Useful against a deployed server.
 *
 * <pre>
 *   ECP_INGEST_URL=https://corrosion-watch-platform.onrender.com/api/ingest/readings \
 *   ECP_SIM_SENSORS="SIM-001=secret1,SIM-002=secret2" \
 *   ECP_SIM_INTERVAL_S=15 \
 *   mvn -q -pl ecp-simulator -am exec:java
 * </pre>
 * Device secrets are shown once when an admin registers a sensor.
 */
public final class SimulatorMain {

    private SimulatorMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        String url = env("ECP_INGEST_URL", "http://localhost:8081/api/ingest/readings");
        String sensors = env("ECP_SIM_SENSORS", "");
        int interval = Integer.parseInt(env("ECP_SIM_INTERVAL_S", "15"));
        if (sensors.isBlank()) {
            System.err.println("Set ECP_SIM_SENSORS=\"DEVICE_ID=secret,...\"");
            System.exit(2);
        }
        try (SimulatorEngine engine = new SimulatorEngine(new HttpReadingSender(url), Clock.systemUTC(), 4)) {
            int i = 0;
            for (String pair : sensors.split(",")) {
                String[] kv = pair.trim().split("=", 2);
                engine.add(new SimulatedSensor(kv[0], kv[1], 21.5 + (i % 4), 45 + 3 * (i % 5), 1.5, i, 0),
                        Duration.ofSeconds(interval));
                i++;
            }
            System.out.printf("Simulating %d sensors every %ds -> %s%n", i, interval, url);
            while (true) {
                Thread.sleep(60_000);
                engine.status().forEach(s -> System.out.printf("%s %s sent=%d failed=%d last=%s%n",
                        s.deviceId(), s.scenario(), s.sent(), s.failed(), s.lastHttpStatus()));
            }
        }
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }
}
