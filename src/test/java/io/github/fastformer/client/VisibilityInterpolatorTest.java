package io.github.fastformer.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VisibilityInterpolatorTest {
   @Test
   void fadesBothDirectionsWithoutJumping() {
      VisibilityInterpolator visibility = new VisibilityInterpolator(100L, true);

      assertEquals(1.0F, visibility.update(false, 0L), 1.0E-6F);
      assertEquals(0.5F, visibility.update(false, 50L), 1.0E-6F);
      assertEquals(0.0F, visibility.update(false, 100L), 1.0E-6F);
      assertEquals(0.5F, visibility.update(true, 150L), 1.0E-6F);
      assertEquals(1.0F, visibility.update(true, 200L), 1.0E-6F);
   }
}
