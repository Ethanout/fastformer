package io.github.fastformer.client.render;

import io.github.fastformer.client.render.HudFadeTimer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HudFadeTimerTest {
   @Test
   void feedbackIsImmediateThenFadesAfterHold() {
      HudFadeTimer timer = new HudFadeTimer(1_500L, 400L);
      timer.touch(1_000L);

      assertEquals(255, timer.alpha(1_000L));
      assertEquals(255, timer.alpha(2_500L));
      assertTrue(timer.alpha(2_700L) > 0);
      assertEquals(0, timer.alpha(2_900L));
   }

   @Test
   void touchingAgainRestartsTheHoldPeriod() {
      HudFadeTimer timer = new HudFadeTimer(1_500L, 400L);
      timer.touch(1_000L);
      timer.touch(2_000L);

      assertEquals(255, timer.alpha(3_500L));
      assertEquals(0, timer.alpha(3_900L));
   }

   @Test
   void elapsedMultiplierAcceleratesFadeWithoutChangingHoldStart() {
      HudFadeTimer timer = new HudFadeTimer(1_000L, 400L);
      timer.touch(0L);

      assertEquals(255, timer.alpha(400L, 2.0));
      assertTrue(timer.alpha(600L, 2.0) > 0);
      assertEquals(0, timer.alpha(700L, 2.0));
   }

   @Test
   void invalidMultiplierFallsBackToNormalSpeed() {
      HudFadeTimer timer = new HudFadeTimer(0L, 100L);
      timer.touch(0L);
      assertTrue(timer.alpha(50L) > 0);
      assertEquals(alphaAtFiftyNanos(1.0), timer.alpha(50L, Double.NaN));
      assertEquals(alphaAtFiftyNanos(1.0), timer.alpha(50L, Double.POSITIVE_INFINITY));
   }

   private static int alphaAtFiftyNanos(double multiplier) {
      HudFadeTimer reference = new HudFadeTimer(0L, 100L);
      reference.touch(0L);
      return reference.alpha(50L, multiplier);
   }

   @Test
   void multiplierDecreaseKeepsConsumedFadeProgress() {
      HudFadeTimer timer = new HudFadeTimer(1_000_000_000L, 400_000_000L);
      timer.touch(0L);

      assertEquals(0, timer.alpha(500_000_000L, 3.0));
      assertEquals(0, timer.alpha(510_000_000L, 1.0));
   }

   @Test
   void multiplierIncreaseKeepsConsumedFadeProgress() {
      HudFadeTimer timer = new HudFadeTimer(1_000_000_000L, 400_000_000L);
      timer.touch(0L);

      assertEquals(0, timer.alpha(1_400_000_000L, 1.0));
      assertEquals(0, timer.alpha(1_410_000_000L, 3.0));
   }

   @Test
   void zeroMultiplierFreezesProgressUntilInput() {
      HudFadeTimer timer = new HudFadeTimer(0L, 400L);
      timer.touch(0L);

      assertEquals(255, timer.alpha(0L, 0.0));
      assertEquals(255, timer.alpha(10_000L, 0.0));
      assertTrue(timer.alpha(10_001L, 1.0) > 0);

      timer.touch(20_000L);

      assertEquals(255, timer.alpha(20_000L, 0.0));
   }

   @Test
   void repeatedReadsAtOneInstantAdvanceTheTimerOneTime() {
      HudFadeTimer timer = new HudFadeTimer(0L, 400L);
      timer.touch(0L);

      int first = timer.alpha(100L, 3.0);

      assertEquals(first, timer.alpha(100L, 3.0));
      assertEquals(first, timer.alpha(100L));
      assertEquals(first, timer.alpha(100L, 1.0));
   }

   @Test
   void newInputRestartsHoldAfterAFullFade() {
      HudFadeTimer timer = new HudFadeTimer(1_000_000_000L, 400_000_000L);
      timer.touch(0L);
      assertEquals(0, timer.alpha(2_000_000_000L, 3.0));

      timer.touch(3_000_000_000L);

      // The first read after the input owns the whole interval, so x3 consumes it.
      assertEquals(255, timer.alpha(3_300_000_000L, 3.0));
      // 300 ms x3 is 900 ms of hold. The next 100 ms x3 adds 300 ms into the fade.
      assertEquals(128, timer.alpha(3_400_000_000L, 3.0));
      assertEquals(0, timer.alpha(3_800_000_000L, 3.0));
   }

   @Test
   void oneShotReadStillAppliesTheMultiplierToTheWholeInterval() {
      HudFadeTimer timer = new HudFadeTimer(1_000_000_000L, 400_000_000L);
      timer.touch(0L);

      assertEquals(0, timer.alpha(500_000_000L, 3.0));
   }

   @Test
   void lowFrameRatesStillUseThePreviousReadAsTheIntervalStart() {
      HudFadeTimer timer = new HudFadeTimer(1_000_000_000L, 400_000_000L);
      timer.touch(0L);

      assertEquals(255, timer.alpha(34_000_000L));
      assertEquals(255, timer.alpha(1_000_000_000L));
      assertEquals(0, timer.alpha(1_400_000_000L));
   }

   @Test
   void clockRollbackDoesNotAdvanceTheTimer() {
      HudFadeTimer timer = new HudFadeTimer(1_000_000_000L, 400_000_000L);
      timer.touch(0L);
      assertEquals(128, timer.alpha(1_200_000_000L));
      assertEquals(128, timer.alpha(1_100_000_000L));
      assertEquals(128, timer.alpha(1_200_000_000L));
      assertEquals(64, timer.alpha(1_300_000_000L));
      assertEquals(0, timer.alpha(1_400_000_000L));
      assertEquals(0, timer.alpha(1_800_000_000L));
   }

   @Test
   void clearRemovesConsumedProgress() {
      HudFadeTimer timer = new HudFadeTimer(1_000_000_000L, 400_000_000L);
      timer.touch(0L);
      assertEquals(0, timer.alpha(2_000_000_000L));

      timer.clear();

      assertEquals(0, timer.alpha(2_100_000_000L));
   }
}
