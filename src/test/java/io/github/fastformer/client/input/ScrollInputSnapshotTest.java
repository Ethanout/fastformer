package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ScrollInputSnapshotTest {
   @Test
   void capturesOnlyNonZeroCandidateDirection() {
      assertEquals(1, new ScrollInputSnapshot(1).direction());
      assertEquals(-1, new ScrollInputSnapshot(-1).direction());
      assertThrows(IllegalArgumentException.class, () -> new ScrollInputSnapshot(0));
   }

   @Test
   void scrollEventsAreInvalidatedWithTheInputSession() {
      var session = new ClientInputSession();
      session.postScroll(new ScrollInputSnapshot(1));
      session.reset();
      var count = new int[1];
      session.drainPhysicalEvents(() -> true, event -> count[0]++, event -> count[0]++);
      assertEquals(0, count[0]);
   }
}
