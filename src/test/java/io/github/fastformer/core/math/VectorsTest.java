package io.github.fastformer.core.math;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class VectorsTest {
   @Test void lengthAndSquaredLengthRetainDifferentCutoffs() {
      assertEquals(1.0, normalize(1.0E-5, 0, 0).x(), 1.0E-15);
      assertEquals(new Value(0, 0, 0), direction(1.0E-5, 0, 0));
      assertEquals(new Value(0, 0, 0), normalize(Math.nextDown(Epsilon.LENGTH), 0, 0));
      assertEquals(1.0, normalize(Epsilon.LENGTH, 0, 0).x(), 1.0E-15);
   }

   @Test void normalizationPreservesExistingArithmeticAcrossMagnitudes() {
      var random = new Random(1729);
      for (int i = 0; i < 2000; i++) {
         double scale = Math.pow(10, random.nextDouble(-12, 12));
         double x = random.nextDouble(-1, 1) * scale;
         double y = random.nextDouble(-1, 1) * scale;
         double z = random.nextDouble(-1, 1) * scale;
         double squared = x * x + y * y + z * z;
         double length = Math.sqrt(squared);
         var scaled = new Value(x * (1 / length), y * (1 / length), z * (1 / length));
         assertEquals(length < Epsilon.LENGTH ? new Value(0, 0, 0) : scaled, normalize(x, y, z));
         var divided = new Value(x / length, y / length, z / length);
         assertEquals(squared < Epsilon.DIRECTION_LENGTH_SQUARED ? new Value(0, 0, 0) : divided, direction(x, y, z));
      }
   }

   @Test void constructsOneOutputAndRejectsInvalidCutoffs() {
      var count = new AtomicInteger();
      Vectors.Factory<Value> factory = (x, y, z) -> { count.incrementAndGet(); return new Value(x, y, z); };
      Vectors.normalize(3, 4, 0, Epsilon.LENGTH, factory);
      assertEquals(1, count.get());
      Vectors.normalizeSquared(0, 0, 0, Epsilon.DIRECTION_LENGTH_SQUARED, factory);
      assertEquals(2, count.get());
      for (double cutoff : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
         assertThrows(IllegalArgumentException.class, () -> Vectors.normalize(1, 0, 0, cutoff, factory));
         assertThrows(IllegalArgumentException.class, () -> Vectors.normalizeSquared(1, 0, 0, cutoff, factory));
      }
      assertEquals(2, count.get());
   }

   @Test void nonFiniteCoordinatesAreNotSilentlyConvertedToZero() {
      assertTrue(Double.isNaN(normalize(Double.POSITIVE_INFINITY, 1, 0).x()));
      assertTrue(Double.isNaN(direction(Double.NaN, 1, 0).x()));
   }

   private static Value normalize(double x, double y, double z) {
      return Vectors.normalize(x, y, z, Epsilon.LENGTH, Value::new);
   }

   private static Value direction(double x, double y, double z) {
      return Vectors.normalizeSquared(x, y, z, Epsilon.DIRECTION_LENGTH_SQUARED, Value::new);
   }

   private record Value(double x, double y, double z) {}
}
