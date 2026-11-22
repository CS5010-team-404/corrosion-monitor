package edu.neu.ecp.simulator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Runs many simulated sensors on a small thread pool and posts their readings to the ingestion API. */
public final class SimulatorEngine implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(SimulatorEngine.class.getName());

    private final ReadingSender sender;
    private final Clock clock;
    private final Map<String, Entry> sensors = new ConcurrentHashMap<>();
    private final ScheduledExecutorService pool;

    public SimulatorEngine(ReadingSender sender, Clock clock, int threads) {
        this.sender = sender;
        this.clock = clock;
        this.pool = Executors.newScheduledThreadPool(threads, r -> {
            Thread t = new Thread(r, "simulator");
            t.setDaemon(true);
            return t;
        });
    }

    public void add(SimulatedSensor sensor, Duration interval) {
        requirePositive(interval);
        Entry entry = new Entry(sensor);
        if (sensors.putIfAbsent(sensor.deviceId(), entry) != null) {
            throw new IllegalArgumentException("duplicate device " + sensor.deviceId());
        }
        long jitterMs = Math.floorMod(sensor.deviceId().hashCode(), (int) Math.max(1, interval.toMillis()));
        schedule(entry, interval, jitterMs);
    }

    /**
     * Changes how often a running device reports (an admin edited its expected interval). The next reading goes out
     * one new interval from now. Returns false if this engine does not run that device.
     */
    public boolean setInterval(String deviceId, Duration interval) {
        requirePositive(interval);
        Entry e = sensors.get(deviceId);
        if (e == null) {
            return false;
        }
        synchronized (e) {
            if (!interval.equals(e.interval)) {
                e.future.cancel(false);
                schedule(e, interval, interval.toMillis());
            }
        }
        return true;
    }

    private void schedule(Entry e, Duration interval, long initialDelayMs) {
        synchronized (e) {
            e.interval = interval;
            e.future = pool.scheduleAtFixedRate(() -> tick(e), initialDelayMs, interval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private static void requirePositive(Duration interval) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
    }

    public boolean setScenario(String deviceId, Scenario scenario) {
        Entry e = sensors.get(deviceId);
        if (e == null) {
            return false;
        }
        e.sensor.setScenario(scenario, clock.instant());
        return true;
    }

    public List<Status> status() {
        List<Status> out = new ArrayList<>();
        for (Entry e : sensors.values()) {
            out.add(new Status(e.sensor.deviceId(), e.sensor.scenario(), e.interval.toSeconds(), e.lastSentAt,
                    e.lastHttpStatus, e.sent.get(), e.failed.get()));
        }
        out.sort(Comparator.comparing(Status::deviceId));
        return out;
    }

    private void tick(Entry e) {
        try {
            Instant now = clock.instant();
            var reading = e.sensor.next(now);
            if (reading.isEmpty()) {
                return;
            }
            var request = e.sensor.sign(List.of(reading.get()), now.getEpochSecond());
            int status = sender.send(request);
            e.lastSentAt = now;
            e.lastHttpStatus = status;
            if (status / 100 == 2) {
                e.sent.incrementAndGet();
            } else {
                e.failed.incrementAndGet();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) { // never let one failed send kill the schedule
            e.failed.incrementAndGet();
            LOG.log(Level.FINE, "send failed for " + e.sensor.deviceId(), ex);
        }
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }

    public record Status(String deviceId, Scenario scenario, long intervalSeconds, Instant lastSentAt,
                         Integer lastHttpStatus, long sent, long failed) {
    }

    private static final class Entry {
        final SimulatedSensor sensor;
        volatile Duration interval;
        ScheduledFuture<?> future;
        final AtomicLong sent = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        volatile Instant lastSentAt;
        volatile Integer lastHttpStatus;

        Entry(SimulatedSensor sensor) {
            this.sensor = sensor;
        }
    }
}
