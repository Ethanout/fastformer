package io.github.fastformer.client.input.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class FastFlightGestureTest {
   @Test
   void doublePressAcceleratesFromTwoToTenAndReleaseStopsIt() {
      var gesture = new FastFlightGesture();
      gesture.press(0);
      assertEquals(1, gesture.multiplier(0));
      gesture.release();
      gesture.press(200_000_000L);
      assertEquals(2, gesture.multiplier(200_000_000L));
      assertEquals(6, gesture.multiplier(1_700_000_000L));
      assertEquals(10, gesture.multiplier(3_200_000_000L));
      assertEquals(10, gesture.multiplier(20_000_000_000L));
      gesture.release();
      assertEquals(1, gesture.multiplier(20_000_000_000L));
   }

   @Test
   void accelerationNeverReversesOrOvershootsAndEasesIntoTheCap() {
      var gesture = new FastFlightGesture();
      gesture.press(0);
      gesture.release();
      gesture.press(200_000_000L);
      double previous = 2;
      for (long elapsed = 0; elapsed <= 7_000_000_000L; elapsed += 10_000_000L) {
         double speed = gesture.multiplier(200_000_000L + elapsed);
         org.junit.jupiter.api.Assertions.assertTrue(speed >= previous && speed <= 10);
         previous = speed;
      }
      org.junit.jupiter.api.Assertions.assertTrue(gesture.multiplier(210_000_000L) - 2 < 0.001);
      org.junit.jupiter.api.Assertions.assertTrue(10 - gesture.multiplier(3_190_000_000L) < 0.001);
   }

   @Test
   void heldKeyRepeatsAndSlowPressesDoNotTriggerBoost() {
      var gesture = new FastFlightGesture();
      gesture.press(0);
      gesture.press(100_000_000L);
      assertEquals(1, gesture.multiplier(100_000_000L));
      gesture.release();
      gesture.press(300_000_000L);
      assertEquals(1, gesture.multiplier(300_000_000L));
      gesture.release();
      gesture.reset();
      gesture.press(400_000_000L);
      assertEquals(1, gesture.multiplier(400_000_000L));
   }
}
