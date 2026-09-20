package io.github.fastformer.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class HoverDwellTrackerTest {
   @Test
   void acceleratesOnlyAfterStableDwell() {
      HoverDwellTracker tracker = new HoverDwellTracker(1_000L, 3.0);
      assertEquals(1.0, tracker.multiplier("a", 0L), 1.0E-9);
      assertEquals(1.0, tracker.multiplier("a", 500L), 1.0E-9);
      assertEquals(3.0, tracker.multiplier("a", 1_000L), 1.0E-9);
      assertEquals(1.0, tracker.multiplier("b", 1_600L), 1.0E-9);
   }

   @Test
   void nullAndClockRewindReset() {
      HoverDwellTracker tracker = new HoverDwellTracker(1_000L, 2.0);
      tracker.multiplier("a", 100L);
      assertEquals(1.0, tracker.multiplier(null, 200L), 1.0E-9);
      assertEquals(1.0, tracker.multiplier("a", 50L), 1.0E-9);
   }

   @Test
   void negativeNanoTimeOriginIsSupported() {
      HoverDwellTracker tracker = new HoverDwellTracker(1_000L, 3.0);
      assertEquals(1.0, tracker.multiplier("a", -2_000L), 1.0E-9);
      assertEquals(1.0, tracker.multiplier("a", -1_500L), 1.0E-9);
      assertEquals(3.0, tracker.multiplier("a", -1_000L), 1.0E-9);
   }
}
