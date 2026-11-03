package edu.neu.ecp.simulator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class HttpReadingSender implements ReadingSender {

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final URI endpoint;

    public HttpReadingSender(String endpoint) {
        this.endpoint = URI.create(endpoint);
    }

    @Override
    public int send(SimulatedSensor.SignedRequest r) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Device-Id", r.deviceId())
                .header("X-Timestamp", Long.toString(r.epochSeconds()))
                .header("X-Signature", r.signature())
                .POST(HttpRequest.BodyPublishers.ofString(r.body()))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
