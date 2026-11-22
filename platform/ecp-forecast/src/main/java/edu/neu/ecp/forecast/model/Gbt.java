package edu.neu.ecp.forecast.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Small gradient-boosted regression trees, plain Java, no native code. Second-order boosting (gradient and hessian
 * per row, leaf value −G/(H+λ)), so one learner covers three losses: squared error, Huber (robust to sudden onsets)
 * and logistic (early-warning probabilities). Features are pre-binned into at most {@code maxBins} quantile bins
 * of the training data; a split stores the real-valued bin edge, so prediction needs only the raw features.
 *
 * <p>Fully deterministic: no row or feature sampling, ties go to the lowest feature and bin. Early stopping keeps the
 * tree count with the best validation score (MAE for regression, log-loss for logistic) and stops after
 * {@code patience} rounds without improvement.
 */
public final class Gbt {

    private Gbt() {
    }

    public enum Loss { SQUARED, HUBER, LOGISTIC }

    /**
     * @param huberDelta residuals beyond this count linearly (HUBER only)
     * @param l2 penalty λ on leaf values
     */
    public record Params(Loss loss, int maxDepth, int minLeaf, double learningRate, int maxTrees, int patience,
                         double huberDelta, int maxBins, double l2) {
        public Params {
            if (maxDepth < 1 || minLeaf < 1 || maxTrees < 1 || maxBins < 2 || maxBins > 256
                    || !(learningRate > 0 && learningRate <= 1) || patience < 1 || l2 < 0) {
                throw new IllegalArgumentException("invalid GBT parameters");
            }
        }

        public static Params of(Loss loss, int maxDepth, int minLeaf) {
            return new Params(loss, maxDepth, minLeaf, 0.05, 500, 30, 1.0, 64, 1.0);
        }

        public Params withTrees(int n) {
            return new Params(loss, maxDepth, minLeaf, learningRate, n, patience, huberDelta, maxBins, l2);
        }

        public Params withHuberDelta(double d) {
            return new Params(loss, maxDepth, minLeaf, learningRate, maxTrees, patience, d, maxBins, l2);
        }

        public Params withLearningRate(double lr) {
            return new Params(loss, maxDepth, minLeaf, lr, maxTrees, patience, huberDelta, maxBins, l2);
        }
    }

    /**
     * One tree in flat arrays. Node 0 is the root; {@code feature[n] < 0} marks a leaf whose {@code value} already
     * includes the learning rate. Internal node: go left when {@code x[feature] <= threshold}.
     */
    public record Tree(int[] feature, double[] threshold, int[] left, int[] right, double[] value) {
        public double predict(double[] x) {
            int n = 0;
            while (feature[n] >= 0) {
                n = x[feature[n]] <= threshold[n] ? left[n] : right[n];
            }
            return value[n];
        }

        public int depth() {
            return depth(0);
        }

        private int depth(int n) {
            return feature[n] < 0 ? 0 : 1 + Math.max(depth(left[n]), depth(right[n]));
        }

        public int nodes() {
            return feature.length;
        }
    }

    /** A fitted ensemble. For LOGISTIC, {@link #predict} is a probability and {@code threshold} the warning cut. */
    public record Model(Loss loss, double base, List<Tree> trees, double threshold) {
        public Model {
            trees = List.copyOf(trees);
        }

        public double raw(double[] x) {
            double s = base;
            for (Tree t : trees) {
                s += t.predict(x);
            }
            return s;
        }

        public double predict(double[] x) {
            double r = raw(x);
            return loss == Loss.LOGISTIC ? sigmoid(r) : r;
        }

        public Model withThreshold(double t) {
            return new Model(loss, base, trees, t);
        }
    }

    /** The model plus how training went: validation score per tree count (index 0 = base only). */
    public record Fit(Model model, int bestTrees, double[] validationCurve) {
    }

    /** Trains with all {@code maxTrees} trees, no validation (used to refit with a tree count chosen earlier). */
    public static Model fit(List<double[]> x, double[] y, double[] weights, Params p) {
        return fit(x, y, weights, null, null, p).model();
    }

    /**
     * @param weights per-row weights (null = all 1)
     * @param xVal validation rows for early stopping (null = no early stopping)
     */
    public static Fit fit(List<double[]> x, double[] y, double[] weights, List<double[]> xVal, double[] yVal, Params p) {
        int n = x.size();
        if (n == 0 || y.length != n || (weights != null && weights.length != n)) {
            throw new IllegalArgumentException("rows, targets and weights must have the same non-zero length");
        }
        if (p.loss() == Loss.LOGISTIC) {
            for (double v : y) {
                if (v != 0 && v != 1) {
                    throw new IllegalArgumentException("logistic targets must be 0 or 1");
                }
            }
        }
        int features = x.get(0).length;
        double[][] cuts = new double[features][];
        for (int f = 0; f < features; f++) {
            cuts[f] = cuts(x, f, p.maxBins());
        }
        byte[][] bins = new byte[features][n];
        for (int f = 0; f < features; f++) {
            for (int i = 0; i < n; i++) {
                bins[f][i] = (byte) bin(cuts[f], x.get(i)[f]);
            }
        }
        double[] w = weights == null ? filled(n, 1.0) : weights.clone();
        double base = baseScore(y, w, p);
        double[] f = filled(n, base);
        boolean validate = xVal != null && !xVal.isEmpty();
        double[] fv = validate ? filled(xVal.size(), base) : null;
        List<Tree> trees = new ArrayList<>();
        List<Double> curve = new ArrayList<>();
        double best = validate ? score(yVal, fv, p) : 0;
        int bestTrees = 0;
        if (validate) {
            curve.add(best);
        }
        double[] g = new double[n];
        double[] h = new double[n];
        int[] all = new int[n];
        for (int i = 0; i < n; i++) {
            all[i] = i;
        }
        for (int t = 0; t < p.maxTrees(); t++) {
            gradients(y, f, w, p, g, h);
            Grower gr = new Grower(bins, cuts, g, h, p);
            gr.grow(all.clone(), 0, n, 0);
            Tree tree = gr.tree();
            trees.add(tree);
            for (int i = 0; i < n; i++) {
                f[i] += tree.predict(x.get(i));
            }
            if (validate) {
                for (int i = 0; i < fv.length; i++) {
                    fv[i] += tree.predict(xVal.get(i));
                }
                double s = score(yVal, fv, p);
                curve.add(s);
                if (s < best - 1e-12) {
                    best = s;
                    bestTrees = trees.size();
                } else if (trees.size() - bestTrees >= p.patience()) {
                    break;
                }
            }
        }
        if (!validate) {
            bestTrees = trees.size();
        }
        Model m = new Model(p.loss(), base, trees.subList(0, bestTrees), 0.5);
        return new Fit(m, bestTrees, curve.stream().mapToDouble(Double::doubleValue).toArray());
    }

    static double sigmoid(double r) {
        return 1 / (1 + Math.exp(-r));
    }

    private static double[] filled(int n, double v) {
        double[] a = new double[n];
        Arrays.fill(a, v);
        return a;
    }

    /** Starting score: weighted mean (squared), weighted median (Huber), log-odds of the weighted rate (logistic). */
    static double baseScore(double[] y, double[] w, Params p) {
        double sw = 0;
        double swy = 0;
        for (int i = 0; i < y.length; i++) {
            sw += w[i];
            swy += w[i] * y[i];
        }
        double mean = swy / sw;
        return switch (p.loss()) {
            case SQUARED -> mean;
            case HUBER -> weightedMedian(y, w);
            case LOGISTIC -> {
                double r = Math.min(1 - 1e-6, Math.max(1e-6, mean));
                yield Math.log(r / (1 - r));
            }
        };
    }

    private static double weightedMedian(double[] y, double[] w) {
        Integer[] idx = new Integer[y.length];
        double total = 0;
        for (int i = 0; i < y.length; i++) {
            idx[i] = i;
            total += w[i];
        }
        Arrays.sort(idx, (a, b) -> Double.compare(y[a], y[b]));
        double acc = 0;
        for (Integer i : idx) {
            acc += w[i];
            if (acc >= total / 2) {
                return y[i];
            }
        }
        return y[idx[idx.length - 1]];
    }

    static void gradients(double[] y, double[] f, double[] w, Params p, double[] g, double[] h) {
        for (int i = 0; i < y.length; i++) {
            switch (p.loss()) {
                case SQUARED -> {
                    g[i] = w[i] * (f[i] - y[i]);
                    h[i] = w[i];
                }
                case HUBER -> {
                    double r = f[i] - y[i];
                    g[i] = w[i] * Math.max(-p.huberDelta(), Math.min(p.huberDelta(), r));
                    h[i] = w[i];
                }
                case LOGISTIC -> {
                    double pr = sigmoid(f[i]);
                    g[i] = w[i] * (pr - y[i]);
                    h[i] = w[i] * Math.max(1e-6, pr * (1 - pr));
                }
                default -> throw new IllegalStateException();
            }
        }
    }

    /** Validation score, lower is better: MAE for regression, mean log-loss for logistic. */
    static double score(double[] y, double[] f, Params p) {
        double s = 0;
        for (int i = 0; i < y.length; i++) {
            if (p.loss() == Loss.LOGISTIC) {
                double pr = Math.min(1 - 1e-12, Math.max(1e-12, sigmoid(f[i])));
                s -= y[i] == 1 ? Math.log(pr) : Math.log(1 - pr);
            } else {
                s += Math.abs(y[i] - f[i]);
            }
        }
        return s / y.length;
    }

    /** Up to {@code maxBins − 1} distinct quantile edges of one feature column. */
    static double[] cuts(List<double[]> x, int f, int maxBins) {
        double[] v = new double[x.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = x.get(i)[f];
            if (!Double.isFinite(v[i])) {
                throw new IllegalArgumentException("feature " + f + " is not finite at row " + i);
            }
        }
        Arrays.sort(v);
        double[] edges = new double[maxBins - 1];
        int k = 0;
        for (int b = 1; b < maxBins; b++) {
            double e = v[(int) ((long) b * (v.length - 1) / maxBins)];
            if (k == 0 || e > edges[k - 1]) {
                edges[k++] = e;
            }
        }
        // the largest value needs no edge: everything above the last edge is the last bin
        if (k > 0 && edges[k - 1] >= v[v.length - 1]) {
            k--;
        }
        return Arrays.copyOf(edges, k);
    }

    /** Bin of a value: the first edge it is at or below, or the number of edges if above all. */
    static int bin(double[] edges, double v) {
        int lo = 0;
        int hi = edges.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (v <= edges[mid]) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    /** Grows one tree depth-first on binned features, recording nodes in flat lists. */
    private static final class Grower {
        private final byte[][] bins;
        private final double[][] cuts;
        private final double[] g;
        private final double[] h;
        private final Params p;
        private final List<int[]> struct = new ArrayList<>(); // {feature, left, right}
        private final List<double[]> vals = new ArrayList<>();   // {threshold, value}

        Grower(byte[][] bins, double[][] cuts, double[] g, double[] h, Params p) {
            this.bins = bins;
            this.cuts = cuts;
            this.g = g;
            this.h = h;
            this.p = p;
        }

        /** Grows the node holding rows {@code idx[from, to)}; returns its node id. */
        int grow(int[] idx, int from, int to, int depth) {
            int node = struct.size();
            struct.add(new int[] {-1, -1, -1});
            vals.add(new double[] {0, 0});
            double gs = 0;
            double hs = 0;
            for (int k = from; k < to; k++) {
                gs += g[idx[k]];
                hs += h[idx[k]];
            }
            int count = to - from;
            int bestF = -1;
            int bestB = -1;
            double bestGain = 1e-12;
            if (depth < p.maxDepth() && count >= 2 * p.minLeaf()) {
                double parent = gs * gs / (hs + p.l2());
                for (int f = 0; f < bins.length; f++) {
                    int nb = cuts[f].length + 1;
                    if (nb < 2) {
                        continue;
                    }
                    double[] hg = new double[nb];
                    double[] hh = new double[nb];
                    int[] hc = new int[nb];
                    byte[] col = bins[f];
                    for (int k = from; k < to; k++) {
                        int i = idx[k];
                        int b = col[i] & 0xFF;
                        hg[b] += g[i];
                        hh[b] += h[i];
                        hc[b]++;
                    }
                    double gl = 0;
                    double hl = 0;
                    int cl = 0;
                    for (int b = 0; b < nb - 1; b++) {
                        gl += hg[b];
                        hl += hh[b];
                        cl += hc[b];
                        int cr = count - cl;
                        if (cl < p.minLeaf()) {
                            continue;
                        }
                        if (cr < p.minLeaf()) {
                            break;
                        }
                        double gr = gs - gl;
                        double hr = hs - hl;
                        double gain = gl * gl / (hl + p.l2()) + gr * gr / (hr + p.l2()) - parent;
                        if (gain > bestGain) {
                            bestGain = gain;
                            bestF = f;
                            bestB = b;
                        }
                    }
                }
            }
            if (bestF < 0) {
                vals.get(node)[1] = -gs / (hs + p.l2()) * p.learningRate();
                return node;
            }
            // partition rows in place: bin <= bestB to the left, order otherwise preserved
            byte[] col = bins[bestF];
            int[] tmp = new int[count];
            int l = 0;
            for (int k = from; k < to; k++) {
                if ((col[idx[k]] & 0xFF) <= bestB) {
                    tmp[l++] = idx[k];
                }
            }
            int r = l;
            for (int k = from; k < to; k++) {
                if ((col[idx[k]] & 0xFF) > bestB) {
                    tmp[r++] = idx[k];
                }
            }
            System.arraycopy(tmp, 0, idx, from, count);
            struct.get(node)[0] = bestF;
            vals.get(node)[0] = cuts[bestF][bestB];
            int left = grow(idx, from, from + l, depth + 1);
            int right = grow(idx, from + l, to, depth + 1);
            struct.get(node)[1] = left;
            struct.get(node)[2] = right;
            return node;
        }

        Tree tree() {
            int m = struct.size();
            int[] feature = new int[m];
            int[] left = new int[m];
            int[] right = new int[m];
            double[] threshold = new double[m];
            double[] value = new double[m];
            for (int k = 0; k < m; k++) {
                feature[k] = struct.get(k)[0];
                left[k] = struct.get(k)[1];
                right[k] = struct.get(k)[2];
                threshold[k] = vals.get(k)[0];
                value[k] = vals.get(k)[1];
            }
            return new Tree(feature, threshold, left, right, value);
        }
    }
}
