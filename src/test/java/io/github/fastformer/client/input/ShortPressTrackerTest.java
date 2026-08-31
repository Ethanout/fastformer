package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShortPressTrackerTest {
   @Test
   void onlyAReleaseWithinTheThresholdTriggersTheAction() {
      ShortPressTracker tracker = new ShortPressTracker();

      tracker.press(100L);
      assertFalse(tracker.release(401L, 250L));

      tracker.press(500L);
      assertTrue(tracker.release(700L, 250L));
      assertFalse(tracker.release(710L, 250L));
   }

   @Test
   void cancelDisarmsThePendingRelease() {
      ShortPressTracker tracker = new ShortPressTracker();
      tracker.press(100L);

      tracker.cancel();

      assertFalse(tracker.release(200L, 250L));
   }
}
