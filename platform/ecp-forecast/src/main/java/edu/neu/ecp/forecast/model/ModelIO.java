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

    /**
     * GBT model file. Each tree is stored in preorder as two arrays: {@code f} (feature index, or −1 for a leaf) and
     * {@code v} (split threshold, or the leaf value). The left child of node n is n + 1; the right child follows the
     * left subtree, so no child indices are needed. Thresholds keep 9 significant digits, leaf values 6.
     */
    public static String gbtToJson(GbtForecaster f) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"type\": \"gbt\",\n  \"version\": \"").append(f.version()).append("\",\n");
        sb.append("  \"features\": [");
        for (int i = 0; i < Features.NAMES.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append('"').append(Features.NAMES.get(i)).append('"');
        }
        sb.append("],\n  \"horizons\": {\n");
        appendModels(sb, f.regressors(), MarginForecaster.HORIZONS);
        sb.append("  },\n  \"classifiers\": {\n");
        appendModels(sb, f.classifiers(), MarginForecaster.WARN_HORIZONS);
        sb.append("  },\n  \"metrics\": {");
        appendMetrics(sb, f.metrics());
        sb.append("\n  }\n}\n");
        return sb.toString();
    }

    private static void appendModels(StringBuilder sb, Map<Integer, Gbt.Model> models, int[] horizons) {
        for (int k = 0; k < horizons.length; k++) {
            Gbt.Model m = models.get(horizons[k]);
            sb.append("    \"").append(horizons[k]).append("\": {\"loss\": \"").append(m.loss()).append("\", \"base\": ")
              .append(num(m.base())).append(", \"threshold\": ").append(num(m.threshold())).append(", \"trees\": [");
            for (int t = 0; t < m.trees().size(); t++) {
                Gbt.Tree tree = m.trees().get(t);
                int[] order = preorder(tree);
                StringBuilder fs = new StringBuilder();
                StringBuilder vs = new StringBuilder();
                for (int i = 0; i < order.length; i++) {
                    int n = order[i];
                    fs.append(i == 0 ? "" : ",").append(tree.feature()[n]);
                    vs.append(i == 0 ? "" : ",").append(tree.feature()[n] < 0
                            ? String.format(Locale.ROOT, "%.6g", tree.value()[n])
                            : String.format(Locale.ROOT, "%.9g", tree.threshold()[n]));
                }
                sb.append(t == 0 ? "\n      " : ",\n      ").append("{\"f\": [").append(fs).append("], \"v\": [").append(vs).append("]}");
            }
            sb.append(m.trees().isEmpty() ? "]}" : "\n    ]}").append(k + 1 < horizons.length ? ",\n" : "\n");
        }
    }

    private static int[] preorder(Gbt.Tree tree) {
        int[] out = new int[tree.nodes()];
        int[] top = {0};
        java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
        stack.push(0);
        while (!stack.isEmpty()) {
            int n = stack.pop();
            out[top[0]++] = n;
            if (tree.feature()[n] >= 0) {
                stack.push(tree.right()[n]);
                stack.push(tree.left()[n]);
            }
        }
        return out;
    }

    private static void appendMetrics(StringBuilder sb, Map<String, Object> metrics) {
        int j = 0;
        for (Map.Entry<String, Object> e : metrics.entrySet()) {
            sb.append(j++ == 0 ? "\n" : ",\n").append("    \"").append(e.getKey()).append("\": ");
            Object v = e.getValue();
            sb.append(v instanceof Number n ? num(n.doubleValue()) : "\"" + String.valueOf(v).replace("\"", "'") + "\"");
        }
    }

    public static GbtForecaster gbtFromJson(String json) {
        Object root = new Parser(json).value();
        if (!(root instanceof Map<?, ?> m) || !"gbt".equals(m.get("type"))) {
            throw new IllegalArgumentException("not a GBT model file");
        }
        Map<Integer, Gbt.Model> regs = readModels((Map<?, ?>) m.get("horizons"), MarginForecaster.HORIZONS);
        Map<Integer, Gbt.Model> cls = readModels((Map<?, ?>) m.get("classifiers"), MarginForecaster.WARN_HORIZONS);
        Map<String, Object> metrics = new LinkedHashMap<>();
        if (m.get("metrics") instanceof Map<?, ?> mm) {
            mm.forEach((key, val) -> metrics.put(String.valueOf(key), val));
        }
        return new GbtForecaster((String) m.get("version"), regs, cls, metrics);
    }

    private static Map<Integer, Gbt.Model> readModels(Map<?, ?> section, int[] horizons) {
        if (section == null) {
            throw new IllegalArgumentException("missing models");
        }
        Map<Integer, Gbt.Model> out = new LinkedHashMap<>();
        for (int h : horizons) {
            Map<?, ?> o = (Map<?, ?>) section.get(String.valueOf(h));
            if (o == null) {
                throw new IllegalArgumentException("missing model for " + h + " min");
            }
            List<Gbt.Tree> trees = new ArrayList<>();
            for (Object t : (List<?>) o.get("trees")) {
                Map<?, ?> tm = (Map<?, ?>) t;
                trees.add(tree(doubles(tm.get("f")), doubles(tm.get("v"))));
            }
            out.put(h, new Gbt.Model(Gbt.Loss.valueOf((String) o.get("loss")), ((Number) o.get("base")).doubleValue(),
                    trees, ((Number) o.get("threshold")).doubleValue()));
        }
        return out;
    }

    /** Rebuilds a tree from its preorder arrays. */
    static Gbt.Tree tree(double[] f, double[] v) {
        int n = f.length;
        if (n == 0 || v.length != n) {
            throw new IllegalArgumentException("malformed tree");
        }
        int[] feature = new int[n];
        int[] left = new int[n];
        int[] right = new int[n];
        double[] threshold = new double[n];
        double[] value = new double[n];
        int end = link(f, v, 0, feature, left, right, threshold, value);
        if (end != n) {
            throw new IllegalArgumentException("malformed tree");
        }
        return new Gbt.Tree(feature, threshold, left, right, value);
    }

    /** Links the subtree starting at {@code pos}; returns the index after it. */
    private static int link(double[] f, double[] v, int pos, int[] feature, int[] left, int[] right,
                            double[] threshold, double[] value) {
        if (pos >= f.length) {
            throw new IllegalArgumentException("malformed tree");
        }
        feature[pos] = (int) f[pos];
        if (feature[pos] < 0) {
            feature[pos] = -1;
            left[pos] = -1;
            right[pos] = -1;
            value[pos] = v[pos];
            return pos + 1;
        }
        threshold[pos] = v[pos];
        left[pos] = pos + 1;
        int r = link(f, v, pos + 1, feature, left, right, threshold, value);
        right[pos] = r;
        return link(f, v, r, feature, left, right, threshold, value);
    }

    /** Loads a GBT model file from the classpath, or null if there is none. */
    public static GbtForecaster loadBundledGbt(String resource) {
        try (InputStream in = ModelIO.class.getResourceAsStream(resource)) {
            return in == null ? null : gbtFromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
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
