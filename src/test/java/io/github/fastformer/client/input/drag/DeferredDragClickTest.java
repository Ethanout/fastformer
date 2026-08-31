package io.github.fastformer.client.input.drag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DeferredDragClickTest {
   @Test
   void shortReleaseEmitsOneStepAndLongDragEmitsNone() {
      DeferredDragClick click = DeferredDragClick.start(1_000L, -1);

      assertTrue(click.awaitingRelease(1_100L, 250L));
      assertTrue(click.shouldAwaitRelease(1_100L, 250L, 0, 0));
      assertFalse(click.shouldAwaitRelease(1_100L, 250L, 1, 0));
      assertEquals(-1, click.releaseSteps(1_100L, 250L));
      assertFalse(click.awaitingRelease(1_251L, 250L));
      assertEquals(0, click.releaseSteps(1_251L, 250L));
      assertEquals(0, DeferredDragClick.none().releaseSteps(1_100L, 250L));
      assertEquals(0, click.cancel().releaseSteps(1_100L, 250L));
   }
}
