package io.github.fastformer.client.interaction.binding.input;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ButtonArbiterTest {
   private static final long PRESS = 1_000_000_000L;
   private static final InputPattern DEFERRED = new InputPattern("right", "hold", 200, "deferred");
   private static long after(int millis) { return PRESS + TimeUnit.MILLISECONDS.toNanos(millis); }

   @Test void shortPressReplaysOnlyOnceWithItsOriginalTimestampAndSnapshot() {
      var arbiter = new ButtonArbiter<Object>();
      Object frozen = new Object();
      assertTrue(arbiter.press(1, PRESS, DEFERRED, frozen));
      var signals = arbiter.release(1, after(75));
      assertEquals(1, signals.size());
      assertEquals(ButtonArbiter.Kind.REPLAY_PRESS, signals.getFirst().kind());
      assertSame(frozen, signals.getFirst().snapshot());
      assertEquals(PRESS, signals.getFirst().pressedAtNanos());
      assertTrue(arbiter.release(1, after(80)).isEmpty());
      assertTrue(arbiter.tick(after(250)).isEmpty());
   }

   @Test void repeatedPressDoesNotDelayThresholdOrReplaceTheFrozenInput() {
      var arbiter = new ButtonArbiter<String>();
      arbiter.press(1, PRESS, DEFERRED, "original");
      assertTrue(arbiter.press(1, after(100), DEFERRED, "repeat"));
      assertTrue(arbiter.tick(after(199)).isEmpty());
      var start = arbiter.tick(after(200));
      assertEquals("original", start.getFirst().snapshot());
      assertEquals(ButtonArbiter.Kind.START, start.getFirst().kind());
      assertTrue(arbiter.tick(after(250)).isEmpty());
      assertEquals(ButtonArbiter.Kind.RELEASE, arbiter.release(1, after(260)).getFirst().kind());
   }

   @Test void thresholdReleaseStartsAndFinishesWithoutNeedingAnIntermediateTick() {
      var arbiter = new ButtonArbiter<String>();
      arbiter.press(1, PRESS, DEFERRED, "input");
      assertEquals(List.of(ButtonArbiter.Kind.START, ButtonArbiter.Kind.RELEASE),
         arbiter.release(1, after(200)).stream().map(ButtonArbiter.Signal::kind).toList());
   }

   @Test void lostFocusCancelsWithoutCommittingOrReplaying() {
      var arbiter = new ButtonArbiter<String>();
      arbiter.press(1, PRESS, DEFERRED, "input");
      arbiter.tick(after(200));
      arbiter.cancel();
      assertTrue(arbiter.release(1, after(300)).isEmpty());
      assertFalse(arbiter.active());
   }

   @Test void unrelatedReleaseAndBackwardTimeCannotCommitTheHold() {
      var arbiter = new ButtonArbiter<String>();
      arbiter.press(1, PRESS, DEFERRED, "input");
      assertTrue(arbiter.release(0, after(300)).isEmpty());
      assertTrue(arbiter.active());
      assertTrue(arbiter.release(1, PRESS - 1).isEmpty());
      assertFalse(arbiter.active());
   }
}
