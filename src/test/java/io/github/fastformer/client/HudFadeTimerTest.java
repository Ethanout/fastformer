package io.github.fastformer.client;

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
}
