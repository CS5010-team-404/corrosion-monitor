package edu.neu.ecp.forecast.eval;

import edu.neu.ecp.forecast.model.GbtForecaster;
import edu.neu.ecp.forecast.model.MarginForecaster;
import edu.neu.ecp.forecast.model.MarginPoint;
import edu.neu.ecp.forecast.model.ModelIO;
import edu.neu.ecp.forecast.train.SimulatedHistory;
import edu.neu.ecp.forecast.train.UciLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/**
 * Runs the whole real-data evaluation of docs/ML_FORECAST.md ("Real-data evaluation protocol") and writes small
 * result tables (CSV + Markdown) to the output directory, plus the candidate model files.
 *
 * <pre>
 *   scripts/evaluate-real-data.sh            (wraps: --csv data/energydata_complete.csv --out docs/results)
 * </pre>
 *
 * Deterministic: the learners use no randomness, the simulator uses the production seeds, and folds run in parallel
 * but independently, so re-running gives the same numbers.
 */
public final class RealDataEvaluation {

    static final double MAIN_OFFSET = 1.5;
    static final double[] OFFSETS = {MAIN_OFFSET, 0.5, 3.0};
    static final int TRAIN_RUNS = 240;
    static final int TEST_RUNS = 60;
    static final int SIM_HOURS = 8;
    static final double CHRONO_TRAIN_SHARE = 0.7;
    static final double VALIDATION_SHARE = 0.2;

    static final String PRODUCTION = "production (sim ridge)";
    static final String RIDGE_REAL = "ridge real";
    static final String RIDGE_SIM_REAL = "ridge sim+real";
    static final String GBT_REAL = "GBT real";
    static final String GBT_SIM_REAL = "GBT sim+real";

    private final Map<Integer, UciLoader.Location> data;
    private final Instant t0;
    private final Instant t1;
    private final MarginForecaster production = ModelIO.loadBundled();
    private final List<Example> simTrain;
    private final List<List<Example>> simTest;
    private final List<String[]> rows = new ArrayList<>();
    private final Map<String, String> chosen = new TreeMap<>();
    private final boolean quick;

    RealDataEvaluation(Map<Integer, UciLoader.Location> data, boolean quick) {
        this.data = data;
        this.quick = quick;
        Instant lo = Instant.MAX;
        Instant hi = Instant.MIN;
        for (UciLoader.Location l : data.values()) {
            for (List<UciLoader.Reading> s : l.segments()) {
                lo = s.get(0).at().isBefore(lo) ? s.get(0).at() : lo;
                hi = s.get(s.size() - 1).at().isAfter(hi) ? s.get(s.size() - 1).at() : hi;
            }
        }
        this.t0 = lo;
        this.t1 = hi;
        int trainRuns = quick ? 20 : TRAIN_RUNS;
        int testRuns = quick ? 6 : TEST_RUNS;
        List<Example> st = new ArrayList<>();
        for (int s = 0; s < trainRuns; s++) {
            st.addAll(scoredOnly(Examples.of(-1, -1, SimulatedHistory.run(1000 + s, SIM_HOURS), 1, 2)));
        }
        this.simTrain = st;
        List<List<Example>> test = new ArrayList<>();
        for (int s = 0; s < testRuns; s++) {
            test.add(Examples.of(-2, s, SimulatedHistory.run(900_000 + s, SIM_HOURS), 1, 1));
        }
        this.simTest = test;
    }

    public static void main(String[] args) throws IOException {
        Path csv = Path.of("data/energydata_complete.csv");
        Path out = Path.of("docs/results");
        Path models = Path.of("data/candidates"); // model files are large (GBT ~1.8 MB): kept out of git
        boolean quick = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--csv" -> csv = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--models" -> models = Path.of(args[++i]);
                case "--quick" -> quick = true;
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        Files.createDirectories(out);
        Files.createDirectories(models);
        long start = System.nanoTime();
        Map<Integer, UciLoader.Location> data = UciLoader.load(csv);
        RealDataEvaluation ev = new RealDataEvaluation(data, quick);
        String md = ev.run(csv, models);
        Files.writeString(out.resolve("real-data-results.md"), md);
        Files.writeString(out.resolve("real-data-metrics.csv"), ev.csv());
        Files.writeString(out.resolve("chosen-hyperparameters.csv"), ev.chosenCsv());
        System.out.println(md);
        System.out.printf(Locale.ROOT, "finished in %.1f min%n", (System.nanoTime() - start) / 6e10);
    }

    // ------------------------------------------------------------------------------------------------ examples

    /** Examples per series for one offset, keyed by location. Series ids are unique across locations. */
    Map<Integer, List<List<Example>>> realExamples(double offset) {
        Map<Integer, List<List<Example>>> out = new LinkedHashMap<>();
        int seriesId = 0;
        for (UciLoader.Location l : data.values()) {
            List<List<Example>> series = new ArrayList<>();
            for (List<UciLoader.Reading> seg : l.segments()) {
                List<MarginPoint> pm = UciLoader.perMinute(seg, offset);
                series.add(Examples.of(l.id(), seriesId++, pm, UciLoader.STEP_MINUTES, UciLoader.STEP_MINUTES));
            }
            out.put(l.id(), series);
        }
        return out;
    }

    static List<Example> scoredOnly(List<Example> ex) {
        return ex.stream().filter(Example::scored).toList();
    }

    Instant at(double share) {
        return t0.plusSeconds((long) (Duration.between(t0, t1).getSeconds() * share));
    }

    /** Training rows whose 60-minute target window ends before {@code end}. */
    static boolean endsBefore(Example e, Instant end) {
        return e.at().plusSeconds(Examples.MAX_HORIZON * 60L).isBefore(end);
    }

    /** Fit / validation split of training rows: validation is the last 20% of [from, end), purged. */
    static Recipes.Split split(List<Example> train, Instant from, Instant end) {
        Instant cutV = from.plusSeconds((long) (Duration.between(from, end).getSeconds() * (1 - VALIDATION_SHARE)));
        List<Example> fit = new ArrayList<>();
        List<Example> val = new ArrayList<>();
        for (Example e : train) {
            if (!e.at().isBefore(cutV)) {
                val.add(e);
            } else if (endsBefore(e, cutV)) {
                fit.add(e);
            }
        }
        return new Recipes.Split(fit, val);
    }

    Recipes.Split withSim(Recipes.Split s) {
        List<Example> fit = new ArrayList<>(s.fit());
        fit.addAll(simTrain);
        return new Recipes.Split(fit, s.validation());
    }

    /** The recipes to train for one experiment, by name. */
    Map<String, Predictor> train(Recipes.Split s, List<String> recipes, String tag) {
        // recipes train in parallel; the map keeps the given order
        List<Predictor> trained = recipes.parallelStream().map(r -> trainOne(s, r, tag)).toList();
        Map<String, Predictor> out = new LinkedHashMap<>();
        trained.forEach(p -> out.put(p.name(), p));
        return out;
    }

    private Predictor trainOne(Recipes.Split s, String r, String tag) {
        return switch (r) {
            case RIDGE_REAL -> {
                MarginForecaster f = Recipes.ridge(tag, s);
                note(tag, r, f.metrics());
                yield Models.ridge(r, f);
            }
            case RIDGE_SIM_REAL -> {
                MarginForecaster f = Recipes.ridge(tag, withSim(s));
                note(tag, r, f.metrics());
                yield Models.ridge(r, f);
            }
            case GBT_REAL -> {
                GbtForecaster f = Recipes.gbt(tag, s);
                note(tag, r, f.metrics());
                yield Models.gbt(r, f);
            }
            case GBT_SIM_REAL -> {
                GbtForecaster f = Recipes.gbt(tag, withSim(s));
                note(tag, r, f.metrics());
                yield Models.gbt(r, f);
            }
            default -> throw new IllegalArgumentException(r);
        };
    }

    private synchronized void note(String tag, String recipe, Map<String, Object> m) {
        m.forEach((k, v) -> chosen.put(tag + "," + recipe + "," + k, String.valueOf(v)));
    }

    List<Predictor> fixedPredictors() {
        return List.of(Models.ridge(PRODUCTION, production), Baselines.persistence(), Baselines.trend(), Baselines.rule());
    }

    // ------------------------------------------------------------------------------------------------ experiments

    /** Scored series per model name, per held-out location. */
    record Lolo(Map<String, Map<Integer, List<Metrics.Scored>>> scored) {
        Metrics.Result result(String model, Predicate<Integer> locations) {
            List<Metrics.Scored> all = new ArrayList<>();
            scored.get(model).forEach((loc, s) -> {
                if (locations.test(loc)) {
                    all.addAll(s);
                }
            });
            return Metrics.aggregate(all);
        }
    }

    Lolo lolo(double offset, List<String> recipes) {
        Map<Integer, List<List<Example>>> ex = realExamples(offset);
        Map<String, Map<Integer, List<Metrics.Scored>>> scored = new ConcurrentHashMap<>();
        IntStream.rangeClosed(1, UciLoader.LOCATIONS).parallel().forEach(k -> {
            List<Example> train = new ArrayList<>();
            ex.forEach((loc, series) -> {
                if (loc != k) {
                    series.forEach(s -> train.addAll(scoredOnly(s)));
                }
            });
            Recipes.Split split = split(train, t0, t1.plusSeconds(1));
            List<Predictor> models = new ArrayList<>(fixedPredictors());
            models.addAll(train(split, recipes, "lolo-" + offset + "-loc" + k).values());
            for (Predictor p : models) {
                List<Metrics.Scored> s = new ArrayList<>();
                for (List<Example> series : ex.get(k)) {
                    s.add(Metrics.score(p, series, UciLoader.STEP_MINUTES));
                }
                scored.computeIfAbsent(p.name(), n -> new ConcurrentHashMap<>()).put(k, s);
            }
        });
        return new Lolo(scored);
    }

    Map<String, Metrics.Result> chrono(double offset, List<String> recipes) {
        Map<Integer, List<List<Example>>> ex = realExamples(offset);
        Instant cut = at(CHRONO_TRAIN_SHARE);
        List<Example> train = new ArrayList<>();
        List<List<Example>> test = new ArrayList<>();
        ex.values().forEach(series -> series.forEach(s -> {
            for (Example e : scoredOnly(s)) {
                if (endsBefore(e, cut)) {
                    train.add(e);
                }
            }
            List<Example> after = s.stream().filter(e -> !e.at().isBefore(cut)).toList();
            if (!after.isEmpty()) {
                test.add(after);
            }
        }));
        List<Predictor> models = new ArrayList<>(fixedPredictors());
        models.addAll(train(split(train, t0, cut), recipes, "chrono-" + offset).values());
        Map<String, Metrics.Result> out = new LinkedHashMap<>();
        models.parallelStream().forEach(p -> {
            List<Metrics.Scored> s = test.stream().map(series -> Metrics.score(p, series, UciLoader.STEP_MINUTES)).toList();
            Metrics.Result r = Metrics.aggregate(s);
            synchronized (out) {
                out.put(p.name(), r);
            }
        });
        return ordered(out, models);
    }

    Metrics.Result onSim(Predictor p) {
        return Metrics.aggregate(simTest.stream().map(s -> Metrics.score(p, s, 1)).toList());
    }

    private static Map<String, Metrics.Result> ordered(Map<String, Metrics.Result> m, List<Predictor> order) {
        Map<String, Metrics.Result> out = new LinkedHashMap<>();
        order.forEach(p -> out.put(p.name(), m.get(p.name())));
        return out;
    }

    /** A final candidate: the recipe trained on all real locations (plus simulated runs if the recipe uses them). */
    record Candidate(String name, Predictor predictor, String json, double inferenceMs,
                     java.util.function.Function<List<MarginPoint>, ?> forecast) {
        Candidate(String name, Predictor predictor, String json, double inferenceMs) {
            this(name, predictor, json, inferenceMs, null);
        }
    }

    Candidate finalCandidate(String recipe) {
        Map<Integer, List<List<Example>>> ex = realExamples(MAIN_OFFSET);
        List<Example> train = new ArrayList<>();
        ex.values().forEach(series -> series.forEach(s -> train.addAll(scoredOnly(s))));
        Recipes.Split s = split(train, t0, t1.plusSeconds(1));
        boolean sim = recipe.equals(RIDGE_SIM_REAL) || recipe.equals(GBT_SIM_REAL);
        if (sim) {
            s = withSim(s);
        }
        String version = (recipe.startsWith("GBT") ? "gbt" : "ridge") + (sim ? "-simreal" : "-real") + "-uci-o"
                + MAIN_OFFSET;
        if (recipe.startsWith("GBT")) {
            GbtForecaster f = Recipes.gbt(version, s);
            note("final", recipe, f.metrics());
            GbtForecaster g = ModelIO.gbtFromJson(ModelIO.gbtToJson(f)); // score exactly what would be shipped
            return new Candidate(recipe, Models.gbt(recipe, g), ModelIO.gbtToJson(f), Double.NaN, g::forecast);
        }
        MarginForecaster f = Recipes.ridge(version, s);
        note("final", recipe, f.metrics());
        MarginForecaster g = ModelIO.fromJson(ModelIO.toJson(f));
        return new Candidate(recipe, Models.ridge(recipe, g), ModelIO.toJson(f), Double.NaN, g::forecast);
    }

    /** Median wall time of one forecast (features + all models) over the simulated test histories, after warm-up. */
    double inferenceMs(java.util.function.Function<List<MarginPoint>, ?> forecast) {
        List<List<MarginPoint>> windows = new ArrayList<>();
        for (int s = 0; s < 20; s++) {
            List<MarginPoint> run = SimulatedHistory.run(900_000 + s, 2);
            for (int t = 45; t < run.size(); t += 3) {
                windows.add(run.subList(t - 45, t));
            }
        }
        for (int i = 0; i < 3000; i++) {
            forecast.apply(windows.get(i % windows.size()));
        }
        int n = 5000;
        double[] ms = new double[n];
        for (int i = 0; i < n; i++) {
            long a = System.nanoTime();
            forecast.apply(windows.get(i % windows.size()));
            ms[i] = (System.nanoTime() - a) / 1e6;
        }
        java.util.Arrays.sort(ms);
        return ms[n / 2];
    }

    // ------------------------------------------------------------------------------------------------ run + report

    String run(Path csv, Path models) throws IOException {
        StringBuilder md = new StringBuilder("# Real-data evaluation results (generated)\n\n")
                .append("Generated by `scripts/evaluate-real-data.sh` (`edu.neu.ecp.forecast.eval.RealDataEvaluation`). ")
                .append("Do not edit by hand; re-run to regenerate. Protocol: docs/ML_FORECAST.md.\n\n")
                .append("Data: `").append(csv.getFileName()).append("`, SHA-256 `").append(sha256(csv)).append("`, ")
                .append(t0).append(" to ").append(t1).append(" (file local time read as UTC).")
                .append(quick ? " **QUICK MODE: reduced simulated data, not the reported numbers.**" : "").append("\n\n");
        cleaning(md);

        List<String> all = List.of(RIDGE_REAL, RIDGE_SIM_REAL, GBT_REAL, GBT_SIM_REAL);
        Lolo main = lolo(MAIN_OFFSET, all);
        Map<String, Metrics.Result> loloPooled = new LinkedHashMap<>();
        Map<String, Metrics.Result> loloIndoor = new LinkedHashMap<>();
        for (String m : main.scored().keySet().stream().sorted(Comparator.ORDER).toList()) {
            loloPooled.put(m, main.result(m, loc -> true));
            loloIndoor.put(m, main.result(m, loc -> loc != UciLoader.OUTDOOR_LOCATION));
            record("lolo", MAIN_OFFSET, "all", m, loloPooled.get(m));
            record("lolo", MAIN_OFFSET, "indoor", m, loloIndoor.get(m));
            for (int loc = 1; loc <= UciLoader.LOCATIONS; loc++) {
                final int l = loc;
                record("lolo", MAIN_OFFSET, "location" + loc, m, main.result(m, x -> x == l));
            }
        }
        md.append("## 1. Leave-one-location-out, offset 1.5 °C, pooled over all 9 held-out locations\n\n");
        regressionTable(md, loloPooled);
        warningTable(md, loloPooled);
        md.append("### Same, the 8 indoor rooms only (location 6 is outdoors)\n\n");
        regressionTable(md, loloIndoor);
        warningTable(md, loloIndoor);
        perLocationTable(md, main);

        Map<String, Metrics.Result> chrono = chrono(MAIN_OFFSET, all);
        chrono.forEach((m, r) -> record("chrono", MAIN_OFFSET, "all", m, r));
        md.append("## 2. Chronological split (first 70% of time trains, last 30% tests), offset 1.5 °C\n\n");
        regressionTable(md, chrono);
        warningTable(md, chrono);

        md.append("## 3. Surface-offset sensitivity (LOLO pooled, all 9 locations)\n\n")
          .append("| Offset | Model | MAE 15* | MAE 30 | MAE 60 | Moving MAE 60 | Events 60 | F1 30 | F1 60 | False alarms/day | Median lead |\n")
          .append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        Map<Double, Map<String, Metrics.Result>> sens = new LinkedHashMap<>();
        sens.put(MAIN_OFFSET, loloPooled);
        for (double off : new double[] {0.5, 3.0}) {
            Lolo l = lolo(off, List.of(RIDGE_REAL, GBT_REAL));
            Map<String, Metrics.Result> pooled = new LinkedHashMap<>();
            for (String m : l.scored().keySet().stream().sorted(Comparator.ORDER).toList()) {
                pooled.put(m, l.result(m, loc -> true));
                record("lolo", off, "all", m, pooled.get(m));
            }
            sens.put(off, pooled);
        }
        for (double off : OFFSETS) {
            for (Map.Entry<String, Metrics.Result> e : sens.get(off).entrySet()) {
                if (e.getKey().contains("sim+real")) {
                    continue;
                }
                Metrics.Result r = e.getValue();
                md.append(String.format(Locale.ROOT, "| %.1f °C | %s | %s | %s | %s | %s | %d | %s | %s | %s | %s |%n", off,
                        e.getKey(), f3(r.mae()[0]), f3(r.mae()[1]), f3(r.mae()[2]), f3(r.maeMoving()[2]), r.events()[1],
                        warnCell(e.getKey(), r.f1()[0]), warnCell(e.getKey(), r.f1()[1]),
                        warnCell(e.getKey(), r.falseAlarmsPerDay()), lead(e.getKey(), r)));
            }
        }
        md.append("\n");

        md.append("## 4. Final candidates (trained on all real locations), scored on the simulated test set\n\n");
        Map<String, Metrics.Result> sim = new LinkedHashMap<>();
        for (Predictor p : fixedPredictors()) {
            sim.put(p.name(), onSim(p));
        }
        // trained in parallel; inference is timed afterwards, one candidate at a time, so timings are not disturbed
        List<Candidate> cands = all.parallelStream().map(this::finalCandidate).toList().stream()
                .map(c -> new Candidate(c.name(), c.predictor(), c.json(), inferenceMs(c.forecast()))).toList();
        for (Candidate c : cands) {
            sim.put(c.name(), onSim(c.predictor()));
            Files.writeString(models.resolve("candidate-" + c.name().replace(' ', '-').replace('+', '-') + ".json"),
                    c.json());
        }
        sim.forEach((m, r) -> record("sim-test", Double.NaN, "all", m, r));
        regressionTable(md, sim);
        warningTable(md, sim);

        md.append("## 5. Acceptance rule (docs/ML_FORECAST.md)\n\n");
        Acceptance.Evidence prodEv = new Acceptance.Evidence(loloPooled.get(PRODUCTION), chrono.get(PRODUCTION),
                sim.get(PRODUCTION));
        double prodMs = inferenceMs(production::forecast);
        md.append(String.format(Locale.ROOT, "Production model: inference %.3f ms median, file %d bytes.%n%n", prodMs,
                ModelIO.toJson(production).getBytes(StandardCharsets.UTF_8).length));
        for (Candidate c : cands) {
            Acceptance.Evidence ev = new Acceptance.Evidence(loloPooled.get(c.name()), chrono.get(c.name()),
                    sim.get(c.name()));
            long bytes = c.json().getBytes(StandardCharsets.UTF_8).length;
            Acceptance.Decision d = Acceptance.decide(ev, prodEv, c.inferenceMs(), bytes);
            md.append("### ").append(c.name()).append(": ").append(d.accepted() ? "**PASSES**" : "**does not pass**")
              .append("\n\n| Check | Result | Candidate vs production |\n|---|---|---|\n");
            for (Acceptance.Check ch : d.checks()) {
                md.append("| ").append(ch.id()).append(" ").append(ch.description()).append(" | ")
                  .append(ch.passed() ? "pass" : "FAIL").append(" | ").append(ch.detail()).append(" |\n");
            }
            md.append("\n");
        }
        md.append("Chosen hyperparameters per fold: `chosen-hyperparameters.csv`. All metrics: `real-data-metrics.csv`.\n");
        md.append("\\* the 15-minute target is interpolated between the real readings at +10 and +20 minutes.\n");
        return md.toString();
    }

    private void cleaning(StringBuilder md) {
        md.append("## Cleaning (per location; rows of the file)\n\n")
          .append("| Location | Rows | Unparsable | Duplicate time | Impossible value | Segments | Short segments (readings) | Readings kept |\n")
          .append("|---|---|---|---|---|---|---|---|\n");
        for (UciLoader.Location l : data.values()) {
            UciLoader.Cleaning c = l.cleaning();
            md.append(String.format(Locale.ROOT, "| %d%s | %d | %d | %d | %d | %d | %d (%d) | %d |%n", l.id(),
                    l.outdoor() ? " (outdoor)" : "", c.rows(), c.unparsable(), c.duplicates(), c.impossible(),
                    c.segments(), c.shortSegments(), c.readingsInShortSegments(), c.kept()));
        }
        md.append("\n");
    }

    private static void regressionTable(StringBuilder md, Map<String, Metrics.Result> res) {
        md.append("| Model | Points | MAE 15* | MAE 30 | MAE 60 | RMSE 15* | RMSE 30 | RMSE 60 | Moving points | Moving MAE 15* | Moving MAE 30 | Moving MAE 60 |\n")
          .append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        res.forEach((m, r) -> {
            if (Double.isNaN(r.mae()[0])) {
                return;
            }
            md.append(String.format(Locale.ROOT, "| %s | %d | %s | %s | %s | %s | %s | %s | %d | %s | %s | %s |%n", m, r.points(),
                    f3(r.mae()[0]), f3(r.mae()[1]), f3(r.mae()[2]), f3(r.rmse()[0]), f3(r.rmse()[1]), f3(r.rmse()[2]),
                    r.movingPoints(), f3(r.maeMoving()[0]), f3(r.maeMoving()[1]), f3(r.maeMoving()[2])));
        });
        md.append("\n");
    }

    private static void warningTable(StringBuilder md, Map<String, Metrics.Result> res) {
        md.append("| Model | Events 30 | P 30 | R 30 | F1 30 | Events 60 | P 60 | R 60 | F1 60 | Crossings | Median lead (min) | Warned ≥10 min | False alarms/day |\n")
          .append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        res.forEach((m, r) -> {
            if (m.equals("persistence")) {
                return;
            }
            md.append(String.format(Locale.ROOT, "| %s | %d | %.2f | %.2f | %.2f | %d | %.2f | %.2f | %.2f | %d | %s | %s | %.2f |%n", m,
                    r.events()[0], r.precision()[0], r.recall()[0], r.f1()[0], r.events()[1], r.precision()[1],
                    r.recall()[1], r.f1()[1], r.crossings(), f0(r.medianLeadMin()), pct(r.warnedAtLeast10Min()),
                    r.falseAlarmsPerDay()));
        });
        md.append("\n");
    }

    private static void perLocationTable(StringBuilder md, Lolo lolo) {
        md.append("### Per held-out location (LOLO, offset 1.5 °C): MAE 15* / 30 / 60 min (°C), warning F1 60, false alarms/day\n\n")
          .append("| Location | Points | Events 60 |");
        List<String> models = lolo.scored().keySet().stream()
                .filter(m -> !m.equals("rule_3C")).sorted(Comparator.ORDER).toList();
        for (String m : models) {
            md.append(' ').append(m).append(" |");
        }
        md.append("\n|---|---|---|").append("---|".repeat(models.size())).append("\n");
        for (int loc = 1; loc <= UciLoader.LOCATIONS; loc++) {
            final int l = loc;
            Metrics.Result first = lolo.result(PRODUCTION, x -> x == l);
            md.append("| ").append(loc).append(loc == UciLoader.OUTDOOR_LOCATION ? " (outdoor)" : "").append(" | ")
              .append(first.points()).append(" | ").append(first.events()[1]).append(" |");
            for (String m : models) {
                Metrics.Result r = lolo.result(m, x -> x == l);
                md.append(' ').append(f2(r.mae()[0])).append(" / ").append(f2(r.mae()[1])).append(" / ").append(f2(r.mae()[2]));
                if (!m.equals("persistence")) {
                    md.append("; F1 ").append(f2(r.f1()[1])).append("; FA ").append(f2(r.falseAlarmsPerDay()));
                }
                md.append(" |");
            }
            md.append('\n');
        }
        md.append("\n");
    }

    /** Model ordering used in tables. */
    enum Comparator implements java.util.Comparator<String> {
        ORDER;

        @Override
        public int compare(String a, String b) {
            List<String> order = List.of(PRODUCTION, RIDGE_REAL, RIDGE_SIM_REAL, GBT_REAL, GBT_SIM_REAL, "persistence",
                    "trend", "rule_3C");
            return Integer.compare(order.indexOf(a), order.indexOf(b));
        }
    }

    private static String warnCell(String model, double v) {
        return model.equals("persistence") ? "–" : f2(v);
    }

    private static String lead(String model, Metrics.Result r) {
        return model.equals("persistence") ? "–" : f0(r.medianLeadMin());
    }

    private void record(String split, double offset, String scope, String model, Metrics.Result r) {
        synchronized (rows) {
            rows.add(new String[] {split, Double.isNaN(offset) ? "" : String.valueOf(offset), scope, model,
                String.valueOf(r.points()), f4(r.mae()[0]), f4(r.mae()[1]), f4(r.mae()[2]), f4(r.rmse()[0]),
                f4(r.rmse()[1]), f4(r.rmse()[2]), String.valueOf(r.movingPoints()), f4(r.maeMoving()[0]),
                f4(r.maeMoving()[1]), f4(r.maeMoving()[2]), String.valueOf(r.events()[0]), f4(r.precision()[0]),
                f4(r.recall()[0]), f4(r.f1()[0]), String.valueOf(r.events()[1]), f4(r.precision()[1]),
                f4(r.recall()[1]), f4(r.f1()[1]), String.valueOf(r.crossings()), f4(r.medianLeadMin()),
                f4(r.warnedAtLeast10Min()), f4(r.falseAlarmsPerDay()), f4(r.days())});
        }
    }

    String csv() {
        StringBuilder sb = new StringBuilder("split,offset_c,scope,model,points,mae15_interp,mae30,mae60,rmse15_interp,rmse30,"
                + "rmse60,moving_points,moving_mae15_interp,moving_mae30,moving_mae60,events30,precision30,recall30,f1_30,"
                + "events60,precision60,recall60,f1_60,crossings,median_lead_min,warned_10min_share,false_alarms_per_day,days\n");
        for (String[] r : rows) {
            sb.append(String.join(",", r)).append('\n');
        }
        return sb.toString();
    }

    String chosenCsv() {
        StringBuilder sb = new StringBuilder("experiment,recipe,setting,value\n");
        chosen.forEach((k, v) -> sb.append(k).append(",\"").append(v).append("\"\n"));
        return sb.toString();
    }

    private static String sha256(Path p) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String f4(double v) {
        return Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%.4f", v);
    }

    private static String f3(double v) {
        return Double.isNaN(v) ? "–" : String.format(Locale.ROOT, "%.3f", v);
    }

    private static String f2(double v) {
        return Double.isNaN(v) ? "–" : String.format(Locale.ROOT, "%.2f", v);
    }

    private static String f0(double v) {
        return Double.isNaN(v) ? "–" : String.format(Locale.ROOT, "%.0f", v);
    }

    private static String pct(double v) {
        return Double.isNaN(v) ? "–" : String.format(Locale.ROOT, "%.0f%%", 100 * v);
    }
}
