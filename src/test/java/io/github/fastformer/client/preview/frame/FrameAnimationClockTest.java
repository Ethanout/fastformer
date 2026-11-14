package io.github.fastformer.client.preview.frame;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class FrameAnimationClockTest {
   @Test void drawingSheetsStayInRangeAndNeverRepeatAcrossSteps() {
      for (int count : new int[] {2, 3, 8}) {
         int previous = 0;
         for (long tick = -100; tick < 100; tick++) {
            int next = FrameAnimationClock.nextSheet(previous, count, tick);
            assertTrue(next >= 1 && next <= count);
            assertNotEquals(previous, next);
            assertEquals(next, FrameAnimationClock.nextSheet(previous, count, tick));
            previous = next;
         }
      }
   }

   @Test void multipleSamplesWithinTheSameSheetDoNotAdvanceTheDrawing() {
      var clock = new FrameAnimationClock(1_000_000_000L);
      var first = clock.sample(1_000_000_000L, 8, 3);
      var same = clock.sample(1_010_000_000L, 8, 3);
      assertEquals(first.drawingSheet(), same.drawingSheet());
      assertNotEquals(first.nanos(), same.nanos());
      assertEquals(0, same.sheet(false));
      assertEquals(same.drawingSheet(), same.sheet(true));
      assertEquals(0.01, same.elapsedSeconds(), 1E-12);
      var next = clock.sample(1_125_000_000L, 8, 3);
      assertNotEquals(same.drawingSheet(), next.drawingSheet());
   }

   @Test void resourceSettingChangesKeepSheetIndicesValid() {
      var clock = new FrameAnimationClock(0);
      clock.sample(2_000_000_000L, 8, 8);
      var changed = clock.sample(2_000_000_000L, 8, 2);
      assertTrue(changed.drawingSheet() <= 2);
      assertEquals(2, FrameAnimationClock.nextSheet(1, 0, 16));
      assertTrue(FrameAnimationClock.nextSheet(8, 3, 16) <= 3);
      assertThrows(IllegalArgumentException.class, () -> clock.sample(0, Double.NaN, 3));
   }

   @Test void frozenPhasePreservesExistingDashAndBreathFormulas() {
      for (long now : new long[] {0, 600_000_000L, 1_200_000_000L, 2_400_000_000L, 12_345_678_901L}) {
         var phase = new FrameAnimationClock(0).sample(now, 8, 3);
         double seconds = (now % 10_000_000_000L) / 1_000_000_000.0;
         assertEquals(seconds * 0.5 % 0.5, phase.dashOffset(0.5, 0.5), 1E-12);
         double fraction = (now % 2_400_000_000L) / 2_400_000_000.0;
         assertEquals((float)(0.5 - Math.cos(fraction * Math.PI * 2) * 0.5), phase.breathPulse(2_400_000_000L));
      }
      var phase = new AnimationPhase(-1, 0, 1);
      assertTrue(phase.dashOffset(0.5, 0.5) >= 0);
      assertTrue(phase.breathPulse(2_400_000_000L) >= 0);
      assertThrows(IllegalArgumentException.class, () -> phase.breathPulse(0));
      assertThrows(IllegalArgumentException.class, () -> phase.dashOffset(1, 0));
   }
}
