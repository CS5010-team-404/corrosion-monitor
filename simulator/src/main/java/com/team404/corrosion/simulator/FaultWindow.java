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

final class FaultWindow {
    enum Type { STUCK, DROPOUT, DRIFT, BAD_VALUES }
    final Type type; final double startH, endH; final double param; // param: drift %RH per hour
    FaultWindow(Type type, double startH, double endH, double param) {
        this.type = type; this.startH = startH; this.endH = endH; this.param = param;
    }
    boolean active(double h) { return h >= startH && h < endH; }
}
