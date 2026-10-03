package io.github.fastformer.fastplace.geometry;

import com.google.gson.JsonArray;
import java.util.List;

/** Shape-preserving cubic Hermite curve with constant ends. */
public final class DistanceCurve {
   public static final int MAX_POINTS = 8;
   public static final DistanceCurve CONSTANT = new DistanceCurve(new double[]{0}, new double[]{1});
   public record Point(float distance, float value, float tangent) { }
   private final List<Point> points;

   private DistanceCurve(double[] x, double[] y) {
      double[] slopes = new double[x.length - 1];
      for (int i = 0; i < slopes.length; i++) slopes[i] = (y[i + 1] - y[i]) / (x[i + 1] - x[i]);
      var result = new java.util.ArrayList<Point>();
      for (int i = 0; i < x.length; i++) {
         double tangent = 0;
         // Flat endpoint tangents join the constant tails without a kink.
         if (i > 0 && i < x.length - 1 && slopes[i - 1] * slopes[i] > 0) {
            double left = x[i] - x[i - 1], right = x[i + 1] - x[i];
            double w1 = 2 * right + left, w2 = right + 2 * left;
            tangent = (w1 + w2) / (w1 / slopes[i - 1] + w2 / slopes[i]);
         }
         result.add(new Point((float)x[i], (float)y[i], (float)tangent));
      }
      points = List.copyOf(result);
   }

   public static DistanceCurve parse(JsonArray nodes) {
      if (nodes.isEmpty() || nodes.size() > MAX_POINTS) throw new IllegalArgumentException("Distance curve needs 1 to 8 points");
      double[] x = new double[nodes.size()], y = new double[nodes.size()];
      for (int i = 0; i < nodes.size(); i++) {
         JsonArray pair = nodes.get(i).getAsJsonArray();
         if (pair.size() != 2) throw new IllegalArgumentException("Distance curve point must be [distance, multiplier]");
         x[i] = pair.get(0).getAsFloat();
         y[i] = pair.get(1).getAsFloat();
         if (!Double.isFinite(x[i]) || !Double.isFinite(y[i]) || x[i] < 0 || x[i] > 1000000
            || y[i] < 0 || y[i] > 32 || (i > 0 && x[i] <= x[i - 1])) {
            throw new IllegalArgumentException("Distance curve needs increasing finite distances and multipliers between 0 and 32");
         }
      }
      return new DistanceCurve(x, y);
   }

   public List<Point> points() { return points; }

   public double at(double distance) {
      Point a = points.getFirst();
      if (distance <= a.distance()) return a.value();
      for (int i = 1; i < points.size(); i++) {
         Point b = points.get(i);
         if (distance < b.distance()) {
            double h = b.distance() - a.distance(), t = (distance - a.distance()) / h;
            double t2 = t * t, t3 = t2 * t;
            double value = (2 * t3 - 3 * t2 + 1) * a.value() + (t3 - 2 * t2 + t) * h * a.tangent()
               + (-2 * t3 + 3 * t2) * b.value() + (t3 - t2) * h * b.tangent();
            return Math.clamp(value, Math.min(a.value(), b.value()), Math.max(a.value(), b.value()));
         }
         a = b;
      }
      return a.value();
   }
}
