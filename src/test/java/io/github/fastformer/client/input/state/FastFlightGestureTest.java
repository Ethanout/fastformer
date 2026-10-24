package io.github.fastformer.client.input.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class FastFlightGestureTest {
   @Test
   void rapidForwardTapsKeepRequestingDashesAfterTheInitialDoubleTap() {
      var gesture = new FastFlightGesture();
      for (int tap = 0; tap < 6; tap++) {
         long now = tap * 120_000_000L;
         gesture.press(now);
         assertEquals(tap > 0, gesture.release(now + 40_000_000L));
      }
      gesture.press(1_000_000_000L);
      assertFalse(gesture.release(1_040_000_000L));
   }

   @Test
   void quickSecondReleaseDashesExactlyOnce() {
      var gesture = new FastFlightGesture();
      gesture.press(0);
      assertFalse(gesture.release(50_000_000L));
      gesture.press(150_000_000L);
      assertTrue(gesture.release(350_000_000L));
      assertFalse(gesture.release(350_000_001L));
      assertEquals(1, gesture.multiplier(350_000_001L));
   }

   @Test
   void longHoldAndLostFocusNeverTurnIntoADash() {
      var gesture = new FastFlightGesture();
      gesture.press(0);
      gesture.release();
      gesture.press(150_000_000L);
      assertFalse(gesture.release(350_000_001L));
      gesture.press(500_000_000L);
      gesture.release();
      gesture.press(600_000_000L);
      gesture.reset();
      assertFalse(gesture.release(650_000_000L));
   }

   @Test
   void ordinaryTapsAndKeyRepeatCannotRequestADash() {
      var gesture = new FastFlightGesture();
      gesture.press(0);
      gesture.press(50_000_000L);
      assertFalse(gesture.release(100_000_000L));
      gesture.press(300_000_000L);
      assertFalse(gesture.release(350_000_000L));
   }

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
