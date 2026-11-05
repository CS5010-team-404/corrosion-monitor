package edu.neu.ecp.forecast.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads and writes the model file (JSON). The format is small and fixed, so a tiny hand-written parser keeps the
 * runtime free of dependencies; anything with Jackson can read the same file too.
 */
public final class ModelIO {

    private ModelIO() {
    }

    public static String toJson(MarginForecaster f) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"version\": \"").append(f.version()).append("\",\n");
        sb.append("  \"features\": [");
        for (int i = 0; i < Features.NAMES.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append('"').append(Features.NAMES.get(i)).append('"');
        }
        sb.append("],\n  \"horizons\": {\n");
        int k = 0;
        for (int h : MarginForecaster.HORIZONS) {
            Ridge.LinearModel m = f.models().get(h);
            sb.append("    \"").append(h).append("\": {\"bias\": ").append(num(m.bias()))
              .append(", \"mean\": ").append(arr(m.mean())).append(", \"std\": ").append(arr(m.std()))
              .append(", \"weights\": ").append(arr(m.weights())).append('}')
              .append(++k < MarginForecaster.HORIZONS.length ? ",\n" : "\n");
        }
        sb.append("  },\n  \"classifiers\": {\n");
        int c = 0;
        for (int h : MarginForecaster.WARN_HORIZONS) {
            Logistic.Classifier m = f.classifiers().get(h);
            sb.append("    \"").append(h).append("\": {\"bias\": ").append(num(m.bias()))
              .append(", \"threshold\": ").append(num(m.threshold()))
              .append(", \"mean\": ").append(arr(m.mean())).append(", \"std\": ").append(arr(m.std()))
              .append(", \"weights\": ").append(arr(m.weights())).append('}')
              .append(++c < MarginForecaster.WARN_HORIZONS.length ? ",\n" : "\n");
        }
        sb.append("  },\n  \"metrics\": {");
        int j = 0;
        for (Map.Entry<String, Object> e : f.metrics().entrySet()) {
            sb.append(j++ == 0 ? "\n" : ",\n").append("    \"").append(e.getKey()).append("\": ");
            Object v = e.getValue();
            sb.append(v instanceof Number n ? num(n.doubleValue()) : "\"" + v + "\"");
        }
        sb.append("\n  }\n}\n");
        return sb.toString();
    }

    public static MarginForecaster fromJson(String json) {
        Object root = new Parser(json).value();
        if (!(root instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("model file must be a JSON object");
        }
        String version = (String) m.get("version");
        Map<?, ?> hs = (Map<?, ?>) m.get("horizons");
        Map<Integer, Ridge.LinearModel> models = new LinkedHashMap<>();
        for (int h : MarginForecaster.HORIZONS) {
            Map<?, ?> o = (Map<?, ?>) hs.get(String.valueOf(h));
            if (o == null) {
                throw new IllegalArgumentException("missing horizon " + h);
            }
            models.put(h, new Ridge.LinearModel(doubles(o.get("mean")), doubles(o.get("std")),
                    doubles(o.get("weights")), ((Number) o.get("bias")).doubleValue()));
        }
        Map<?, ?> cs = (Map<?, ?>) m.get("classifiers");
        Map<Integer, Logistic.Classifier> classifiers = new LinkedHashMap<>();
        for (int h : MarginForecaster.WARN_HORIZONS) {
            Map<?, ?> o = (Map<?, ?>) cs.get(String.valueOf(h));
            if (o == null) {
                throw new IllegalArgumentException("missing classifier " + h);
            }
            classifiers.put(h, new Logistic.Classifier(doubles(o.get("mean")), doubles(o.get("std")),
                    doubles(o.get("weights")), ((Number) o.get("bias")).doubleValue(),
                    ((Number) o.get("threshold")).doubleValue()));
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        if (m.get("metrics") instanceof Map<?, ?> mm) {
            mm.forEach((key, val) -> metrics.put(String.valueOf(key), val));
        }
        return new MarginForecaster(version, models, classifiers, metrics);
    }

    public static MarginForecaster load(InputStream in) throws IOException {
        return fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }

    /** Loads the model bundled in this jar. */
    public static MarginForecaster loadBundled() {
        try (InputStream in = ModelIO.class.getResourceAsStream("/models/margin-forecast.json")) {
            if (in == null) {
                throw new IllegalStateException("bundled model /models/margin-forecast.json not found");
            }
            return load(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static double[] doubles(Object o) {
        List<?> l = (List<?>) o;
        double[] d = new double[l.size()];
        for (int i = 0; i < d.length; i++) {
            d[i] = ((Number) l.get(i)).doubleValue();
        }
        return d;
    }

    private static String arr(double[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(num(a[i]));
        }
        return sb.append(']').toString();
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.10g", v);
    }

    /** Minimal JSON parser: objects, arrays, strings (no escapes beyond \"), numbers, true/false/null. */
    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') {
                return object();
            }
            if (c == '[') {
                return array();
            }
            if (c == '"') {
                return string();
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            return Double.parseDouble(s.substring(start, i));
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                i++; // :
                m.put(k, value());
                ws();
                if (s.charAt(i++) == '}') {
                    return m;
                }
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(value());
                ws();
                if (s.charAt(i++) == ']') {
                    return l;
                }
            }
        }

        String string() {
            i++;
            StringBuilder sb = new StringBuilder();
            while (s.charAt(i) != '"') {
                if (s.charAt(i) == '\\') {
                    i++;
                }
                sb.append(s.charAt(i++));
            }
            i++;
            return sb.toString();
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }
}
