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

final class Psychro {
    private static final double B = 17.62, C = 243.12;
    static double satPressureHpa(double tC) { return 6.112 * Math.exp(B * tC / (C + tC)); }
    static double rhFromDewPoint(double tC, double tdC) {
        return Math.max(0, Math.min(100, 100.0 * satPressureHpa(tdC) / satPressureHpa(tC)));
    }
}
