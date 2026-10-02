package io.github.fastformer.client.render.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class StrokeDashCoordinatesTest {
   @Test
   void reversingTheEdgeKeepsDashPositionsAtPhysicalPoints() {
      Vec3 from = new Vec3(8, 64, -3);
      Vec3 to = new Vec3(-12, 68, 9);
      var forward = StrokeDashCoordinates.forLine(from, to);
      var reverse = StrokeDashCoordinates.forLine(to, from);
      for (double t : new double[] {-0.1, 0, 0.25, 0.5, 0.75, 1, 1.1}) {
         assertEquals(forward.at(t), reverse.at(1 - t), 1.0E-12);
      }
   }

   @Test
   void largeWorldCoordinatesDoNotAffectDashPhase() {
      Vec3 to = new Vec3(4, 3, 0);
      Vec3 offset = new Vec3(1000000, 80, -2000000);
      assertEquals(StrokeDashCoordinates.forLine(Vec3.ZERO, to),
         StrokeDashCoordinates.forLine(offset, offset.add(to)));
   }

   @Test
   void subdivisionsAndOvershootsKeepTheOriginalEdgeMetric() {
      var dash = StrokeDashCoordinates.forLine(Vec3.ZERO, new Vec3(4, 0, 0));
      for (int i = 0; i <= 4; i++) assertEquals(i, dash.at(i / 4.0));
      assertEquals(-0.2, dash.at(-0.2 / 4), 1.0E-12);
      assertEquals(4.3, dash.at(1 + 0.3 / 4), 1.0E-12);
   }
}
