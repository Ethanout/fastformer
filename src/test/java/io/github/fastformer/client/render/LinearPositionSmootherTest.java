package io.github.fastformer.client.render;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class LinearPositionSmootherTest {
   @Test
   void stepCompletesLinearlyWithinOneTickAtDifferentFrameRates() {
      for (int frame : new int[]{1, 5, 10, 25}) {
         var smoother = new LinearPositionSmoother(50);
         smoother.update(Vec3.ZERO, 0);
         var target = new Vec3(100, 200, 0);
         for (int elapsed = 0; elapsed <= 50; elapsed += frame) {
            var actual = smoother.update(target, 10 + elapsed);
            assertEquals(elapsed * 2, actual.x, 0.0001);
            assertEquals(elapsed * 4, actual.y, 0.0001);
         }
         assertEquals(target, smoother.update(target, 60));
      }
   }

   @Test
   void shortSpikeIsReducedInsteadOfReplayedAfterADelay() {
      var smoother = new LinearPositionSmoother(100);
      smoother.update(Vec3.ZERO, 0);
      smoother.update(new Vec3(100, 0, 0), 10);
      assertEquals(10, smoother.update(Vec3.ZERO, 20).x, 0.0001);
      assertEquals(10, smoother.update(Vec3.ZERO, 60).x, 0.0001);
      assertEquals(Vec3.ZERO, smoother.update(Vec3.ZERO, 120));
   }

   @Test
   void resetAndHiddenLabelsDoNotReuseAnOldPosition() {
      var smoother = new LinearPositionSmoother(50);
      smoother.update(Vec3.ZERO, 0);
      var next = new Vec3(20, 40, 0);
      assertEquals(next, smoother.update(next, 100));
      smoother.reset();
      assertEquals(Vec3.ZERO, smoother.update(Vec3.ZERO, 101));
      assertEquals(next, smoother.update(next, 5));
   }
}
