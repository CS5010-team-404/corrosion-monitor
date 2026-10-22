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

final class ScenarioEvent {
    enum Target { TEMP, DEWPOINT }

    final Target target;
    final double startH, rampUpH, holdH, rampDownH, delta;

    ScenarioEvent(Target target, double startH, double rampUpH, double holdH, double rampDownH, double delta) {
        this.target = target; this.startH = startH; this.rampUpH = rampUpH;
        this.holdH = holdH; this.rampDownH = rampDownH; this.delta = delta;
    }

    /** Offset in C at simulated hour h (0 before start, delta during hold, back to 0 after). */
    double offsetAt(double h) {
        double t = h - startH;
        if (t <= 0) return 0;
        if (t < rampUpH) return delta * (t / rampUpH);
        t -= rampUpH;
        if (t < holdH) return delta;
        t -= holdH;
        if (t < rampDownH) return delta * (1 - t / rampDownH);
        return 0;
    }
}
