package io.github.fastformer.client.render.geometry;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class BoilClockTest {
   @Test
   void sheetsStayInRangeAndNeverRepeatImmediately() {
      for (int count : new int[] {2, 3, 8}) {
         int previous = 0;
         for (long tick = -100; tick < 100; tick++) {
            int next = BoilClock.next(previous, count, tick);
            assertTrue(next >= 1 && next <= count);
            assertNotEquals(previous, next);
            assertEquals(next, BoilClock.next(previous, count, tick));
            previous = next;
         }
      }
   }

   @Test
   void changingSheetCountAndInvalidCountsRemainSafe() {
      assertTrue(BoilClock.next(8, 3, 16) <= 3);
      assertEquals(2, BoilClock.next(1, 0, 16));
      assertEquals(0, BoilClock.sheet(false));
   }
}
