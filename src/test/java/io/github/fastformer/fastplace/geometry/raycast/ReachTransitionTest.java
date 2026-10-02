package io.github.fastformer.fastplace.geometry.raycast;

import io.github.fastformer.fastplace.settings.ReachThresholds;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReachTransitionTest {
   @Test
   void holdsOwnerBetweenStrictThresholds() {
      var gate = new ReachTransition();
      var defaults = ReachThresholds.DEFAULT;
      assertFalse(gate.update(3, defaults));
      assertFalse(gate.update(12, defaults));
      assertTrue(gate.update(2.99, defaults));
      assertTrue(gate.update(3, defaults));
      assertTrue(gate.update(20, defaults));
      assertFalse(gate.update(20.01, defaults));
      assertFalse(gate.update(12, defaults));
   }

   @Test
   void returnDistanceReadsCurrentAttribute() {
      var gate = new ReachTransition();
      assertTrue(gate.update(2, ReachThresholds.DEFAULT, 20));
      assertTrue(gate.update(18, ReachThresholds.DEFAULT, 20));
      assertFalse(gate.update(18, ReachThresholds.DEFAULT, 16));
      assertTrue(gate.update(2, ReachThresholds.DEFAULT, 16));
      assertTrue(gate.update(24, ReachThresholds.DEFAULT, 30));
   }

   @Test
   void customThresholdsMissAndReset() {
      var gate = new ReachTransition();
      var thresholds = new ReachThresholds(8, 40);
      assertTrue(gate.update(7, thresholds));
      assertTrue(gate.update(35, thresholds));
      assertFalse(gate.update(Double.POSITIVE_INFINITY, thresholds));
      assertFalse(gate.update(35, thresholds));
      assertTrue(gate.update(7, thresholds));
      gate.reset();
      assertFalse(gate.vanilla());
      assertFalse(gate.update(0, new ReachThresholds(0, 20)));
      assertThrows(IllegalArgumentException.class, () -> new ReachThresholds(20, 5));
      assertThrows(IllegalArgumentException.class, () -> new ReachThresholds(5, 5));
   }
}
