package edu.neu.ecp.forecast.model;

import java.util.List;

/**
 * Ridge regression (linear least squares with an L2 penalty), solved in closed form on standardised features.
 * No libraries: a small symmetric system solved by Gaussian elimination, which is plenty for ten features.
 */
public final class Ridge {

    private Ridge() {
    }

    public static LinearModel fit(List<double[]> rows, double[] y, double lambda) {
        return fitWeighted(rows, y, null, lambda);
    }

    /**
     * Robust regression with the Huber loss, by iteratively reweighted ridge: residuals larger than {@code delta}
     * get weight delta/|r|. Sudden, unpredictable onsets (a dehumidifier failing) produce huge errors that would
     * otherwise drag a least-squares fit away from the many ordinary minutes.
     */
    public static LinearModel fitHuber(List<double[]> rows, double[] y, double lambda, double delta, int iterations) {
        double[] w = new double[y.length];
        java.util.Arrays.fill(w, 1.0);
        LinearModel m = fitWeighted(rows, y, w, lambda);
        for (int it = 0; it < iterations; it++) {
            for (int i = 0; i < y.length; i++) {
                double r = Math.abs(y[i] - m.predict(rows.get(i)));
                w[i] = r <= delta ? 1.0 : delta / r;
            }
            m = fitWeighted(rows, y, w, lambda);
        }
        return m;
    }

    static LinearModel fitWeighted(List<double[]> rows, double[] y, double[] sw, double lambda) {
        int n = rows.size();
        int p = rows.get(0).length;
        if (n != y.length || n < p + 1) {
            throw new IllegalArgumentException("need at least " + (p + 1) + " rows, got " + n);
        }
        double[] mean = new double[p];
        double[] std = new double[p];
        for (double[] r : rows) {
            for (int j = 0; j < p; j++) {
                mean[j] += r[j];
            }
        }
        for (int j = 0; j < p; j++) {
            mean[j] /= n;
        }
        for (double[] r : rows) {
            for (int j = 0; j < p; j++) {
                std[j] += (r[j] - mean[j]) * (r[j] - mean[j]);
            }
        }
        for (int j = 0; j < p; j++) {
            std[j] = Math.sqrt(std[j] / n);
            if (std[j] < 1e-12) {
                std[j] = 1; // constant feature: leave unscaled, its weight will be ~0
            }
        }
        double yMean = 0;
        double wSum = 0;
        for (int i = 0; i < n; i++) {
            double wi = sw == null ? 1 : sw[i];
            yMean += wi * y[i];
            wSum += wi;
        }
        yMean /= wSum;
        double[] zMean = new double[p];
        for (int i = 0; i < n; i++) {
            double wi = sw == null ? 1 : sw[i];
            double[] r = rows.get(i);
            for (int j = 0; j < p; j++) {
                zMean[j] += wi * (r[j] - mean[j]) / std[j];
            }
        }
        for (int j = 0; j < p; j++) {
            zMean[j] /= wSum;
        }

        double[][] a = new double[p][p];
        double[] b = new double[p];
        double[] z = new double[p];
        for (int i = 0; i < n; i++) {
            double[] r = rows.get(i);
            double wi = sw == null ? 1 : sw[i];
            for (int j = 0; j < p; j++) {
                z[j] = (r[j] - mean[j]) / std[j] - zMean[j];
            }
            double yc = y[i] - yMean;
            for (int j = 0; j < p; j++) {
                b[j] += wi * z[j] * yc;
                for (int k = j; k < p; k++) {
                    a[j][k] += wi * z[j] * z[k];
                }
            }
        }
        for (int j = 0; j < p; j++) {
            for (int k = 0; k < j; k++) {
                a[j][k] = a[k][j];
            }
            a[j][j] += lambda * wSum;
        }
        double[] w = solve(a, b);
        double bias = yMean;
        for (int j = 0; j < p; j++) {
            bias -= w[j] * zMean[j]; // fold the weighted centring back into the intercept
        }
        return new LinearModel(mean, std, w, bias);
    }

    /** Gaussian elimination with partial pivoting. */
    static double[] solve(double[][] a, double[] b) {
        int p = b.length;
        double[][] m = new double[p][p + 1];
        for (int i = 0; i < p; i++) {
            System.arraycopy(a[i], 0, m[i], 0, p);
            m[i][p] = b[i];
        }
        for (int col = 0; col < p; col++) {
            int pivot = col;
            for (int r = col + 1; r < p; r++) {
                if (Math.abs(m[r][col]) > Math.abs(m[pivot][col])) {
                    pivot = r;
                }
            }
            double[] tmp = m[col];
            m[col] = m[pivot];
            m[pivot] = tmp;
            if (Math.abs(m[col][col]) < 1e-12) {
                throw new IllegalStateException("singular system");
            }
            for (int r = col + 1; r < p; r++) {
                double f = m[r][col] / m[col][col];
                for (int c = col; c <= p; c++) {
                    m[r][c] -= f * m[col][c];
                }
            }
        }
        double[] x = new double[p];
        for (int r = p - 1; r >= 0; r--) {
            double s = m[r][p];
            for (int c = r + 1; c < p; c++) {
                s -= m[r][c] * x[c];
            }
            x[r] = s / m[r][r];
        }
        return x;
    }

    /** A fitted model: prediction = bias + Σ weight_j × (x_j − mean_j) / std_j. */
    public record LinearModel(double[] mean, double[] std, double[] weights, double bias) {
        public double predict(double[] x) {
            double s = bias;
            for (int j = 0; j < x.length; j++) {
                s += weights[j] * (x[j] - mean[j]) / std[j];
            }
            return s;
        }
    }
}
