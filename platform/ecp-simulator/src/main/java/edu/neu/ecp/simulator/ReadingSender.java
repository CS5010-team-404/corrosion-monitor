package edu.neu.ecp.simulator;

import java.io.IOException;

/** Delivers a signed request. Returns the HTTP status code. */
public interface ReadingSender {
    int send(SimulatedSensor.SignedRequest request) throws IOException, InterruptedException;
}
