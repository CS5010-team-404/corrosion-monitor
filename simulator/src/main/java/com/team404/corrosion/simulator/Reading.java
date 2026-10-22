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

record Reading(String sensorId, long seq, Instant ts, double temperatureC, double humidityPct, boolean buffered) {
    String toJson() {
        return String.format(Locale.ROOT,
            "{\"sensorId\":\"%s\",\"seq\":%d,\"ts\":\"%s\",\"temperatureC\":%.2f,\"humidityPct\":%.2f,\"buffered\":%b}",
            sensorId, seq, ts, temperatureC, humidityPct, buffered);
    }
}
