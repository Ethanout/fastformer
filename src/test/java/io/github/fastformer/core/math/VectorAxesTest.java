package io.github.fastformer.core.math;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class VectorAxesTest {
   private record Point(double x, double y, double z) {}

   @Test
   void absoluteMagnitudeAndTiesKeepXThenYThenZPriority() {
      assertEquals(Axis.X, Vectors.dominantAxis(-4, 4, -4));
      assertEquals(Axis.Y, Vectors.dominantAxis(1, -4, 4));
      assertEquals(Axis.Z, Vectors.dominantAxis(1, 2, -4));
      assertEquals(Axis.X, Vectors.dominantAxis(0, -0.0, 0));
      assertEquals(Axis.X, Vectors.dominantAxis(Double.POSITIVE_INFINITY, 1, 2));
      assertEquals(Axis.Z, Vectors.dominantAxis(Double.NaN, Double.NaN, 1));
   }

   @Test
   void randomInputsMatchTheExistingSelectionAndStrokeDecision() {
      var random = new Random(0x47524944L);
      for (int index = 0; index < 10_000; index++) {
         double x = random.nextGaussian(), y = random.nextGaussian(), z = random.nextGaussian();
         int expected = Math.abs(x) >= Math.abs(y) && Math.abs(x) >= Math.abs(z) ? 0 : Math.abs(y) >= Math.abs(z) ? 1 : 2;
         assertEquals(expected, Vectors.dominantAxis(x, y, z).ordinal());
      }
   }

   @Test
   void componentAndAxisVectorPreserveTheChosenValueIncludingSignedZero() {
      for (var axis : Axis.values()) {
         assertEquals(axis.ordinal() + 1, Vectors.component(1, 2, 3, axis));
         var point = Vectors.axisVector(axis, -0.0, Point::new);
         double[] coordinates = {point.x(), point.y(), point.z()};
         for (int index = 0; index < 3; index++) {
            assertEquals(index == axis.ordinal() ? Long.MIN_VALUE : 0L, Double.doubleToRawLongBits(coordinates[index]));
         }
      }
   }
}
