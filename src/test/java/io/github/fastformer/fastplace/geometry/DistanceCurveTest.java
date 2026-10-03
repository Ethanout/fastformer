package io.github.fastformer.fastplace.geometry;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DistanceCurveTest {
   private DistanceCurve curve(String json) {
      return DistanceCurve.parse(JsonParser.parseString(json).getAsJsonArray());
   }

   @Test void passesThroughNodesAndKeepsConstantTails() {
      var c = curve("[[0,1],[5,1],[20,0.65],[64,0.35]]");
      assertEquals(1, c.at(3));
      assertEquals(1, c.at(5));
      assertEquals(0.65, c.at(20), 1e-6);
      assertEquals(0.35, c.at(1000), 1e-6);
      double previous = 1;
      for (double d = 0; d < 100; d += 0.1) {
         double value = c.at(d);
         assertTrue(value <= previous + 1e-8);
         assertTrue(value >= 0.35 - 1e-6);
         previous = value;
      }
   }

   @Test void unequalIntervalsAndTurningPointsDoNotOvershoot() {
      var c = curve("[[0,0],[0.1,2],[40,1],[41,3]]");
      for (int i = 1; i < c.points().size(); i++) {
         var a = c.points().get(i - 1); var b = c.points().get(i);
         for (int j = 0; j <= 100; j++) {
            double v = c.at(a.distance() + (b.distance() - a.distance()) * j / 100.0);
            assertTrue(v >= Math.min(a.value(), b.value()) - 1e-6);
            assertTrue(v <= Math.max(a.value(), b.value()) + 1e-6);
         }
      }
   }

   @Test void invalidNodesAreRejected() {
      for (String json : new String[]{"[]", "[[5,1],[5,0]]", "[[5,1],[0,1]]", "[[0,-1]]", "[[0,1,2]]"}) {
         assertThrows(IllegalArgumentException.class, () -> curve(json));
      }
   }
}
