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

final class Publisher {
    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl, sensorId;
    private final byte[] secret;
    private final Deque<Reading> buffer = new ArrayDeque<>();
    boolean networkDown = false;                       // toggled by the NETWORK_OUTAGE scenario

    Publisher(String baseUrl, String sensorId, String secret) {
        this.baseUrl = baseUrl; this.sensorId = sensorId; this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    void publish(Reading r) {
        if (networkDown) { buffer.addLast(r); return; }
        flushBuffer();
        send(r.toJson());
    }

    /** Replay buffered readings with their ORIGINAL seq and buffered=true. */
    private void flushBuffer() {
        while (!buffer.isEmpty()) {
            Reading b = buffer.pollFirst();
            send(new Reading(b.sensorId(), b.seq(), b.ts(), b.temperatureC(), b.humidityPct(), true).toJson());
        }
    }

    private void send(String body) {
        try {
            String nonce = UUID.randomUUID().toString();
            String signature = hmacHex(sensorId + "." + nonce + "." + body);
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/readings"))
                .header("Content-Type", "application/json")
                .header("X-Sensor-Id", sensorId)
                .header("X-Nonce", nonce)
                .header("X-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) System.err.println("Rejected: " + resp.statusCode() + " " + resp.body());
        } catch (Exception e) {
            System.err.println("Send failed: " + e.getMessage());
        }
    }

    private String hmacHex(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
