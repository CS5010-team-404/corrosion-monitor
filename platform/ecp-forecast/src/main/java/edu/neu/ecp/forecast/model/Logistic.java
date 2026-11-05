package edu.neu.ecp.forecast.model;

import java.util.List;

/**
 * L2-regularised logistic regression fitted by Newton's method (iteratively reweighted least squares) on
 * standardised features. Used for the early-warning question: "will the margin reach the critical level
 * within the next h minutes?". Positive examples can be up-weighted because warnings are rare but costly to miss.
 */
public final class Logistic {

    private Logistic() {
    }

    public static Classifier fit(List<double[]> rows, boolean[] label, double lambda, double positiveWeight, int iterations) {
        int n = rows.size();
        int p = rows.get(0).length;
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
            std[j] = Math.max(1e-12, Math.sqrt(std[j] / n));
        }
        int q = p + 1; // last coefficient is the intercept
        double[] beta = new double[q];
        double[] z = new double[q];
        for (int it = 0; it < iterations; it++) {
            double[][] hess = new double[q][q];
            double[] grad = new double[q];
            for (int i = 0; i < n; i++) {
                double[] r = rows.get(i);
                for (int j = 0; j < p; j++) {
                    z[j] = (r[j] - mean[j]) / std[j];
                }
                z[p] = 1;
                double eta = 0;
                for (int j = 0; j < q; j++) {
                    eta += beta[j] * z[j];
                }
                double mu = 1 / (1 + Math.exp(-eta));
                double w = label[i] ? positiveWeight : 1.0;
                double g = w * ((label[i] ? 1 : 0) - mu);
                double h = w * mu * (1 - mu);
                for (int j = 0; j < q; j++) {
                    grad[j] += g * z[j];
                    for (int k = j; k < q; k++) {
                        hess[j][k] += h * z[j] * z[k];
                    }
                }
            }
            for (int j = 0; j < q; j++) {
                for (int k = 0; k < j; k++) {
                    hess[j][k] = hess[k][j];
                }
                if (j < p) {
                    hess[j][j] += lambda * n;
                    grad[j] -= lambda * n * beta[j];
                }
                hess[j][j] += 1e-9;
            }
            double[] step = Ridge.solve(hess, grad);
            double norm = 0;
            for (int j = 0; j < q; j++) {
                beta[j] += step[j];
                norm += step[j] * step[j];
            }
            if (norm < 1e-10) {
                break;
            }
        }
        double[] w = new double[p];
        System.arraycopy(beta, 0, w, 0, p);
        return new Classifier(mean, std, w, beta[p], 0.5);
    }

    /** Probability = sigmoid(bias + Σ w_j (x_j − mean_j)/std_j); warn when probability ≥ threshold. */
    public record Classifier(double[] mean, double[] std, double[] weights, double bias, double threshold) {
        public double probability(double[] x) {
            double eta = bias;
            for (int j = 0; j < x.length; j++) {
                eta += weights[j] * (x[j] - mean[j]) / std[j];
            }
            return 1 / (1 + Math.exp(-eta));
        }

        public Classifier withThreshold(double t) {
            return new Classifier(mean, std, weights, bias, t);
        }
    }
}
