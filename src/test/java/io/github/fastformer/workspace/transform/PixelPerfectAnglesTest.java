package io.github.fastformer.workspace.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PixelPerfectAnglesTest {
   @Test
   void snapsToSmallIntegerVoxelSlopesAcrossQuadrants() {
      assertEquals(Math.atan2(1, 10), PixelPerfectAngles.snap(Math.toRadians(5.5)), 1.0E-12);
      assertEquals(Math.atan2(2, 1), PixelPerfectAngles.snap(Math.toRadians(64.0)), 1.0E-12);
      assertEquals(Math.atan2(-4, 9), PixelPerfectAngles.snap(Math.toRadians(-25.0)), 1.0E-12);
      assertEquals(Math.PI, Math.abs(PixelPerfectAngles.snap(Math.toRadians(179.0))), 1.0E-12);
   }

   @Test
   void defaultAndControlRotationPoliciesAreExplicit() {
      assertEquals(Math.PI / 2.0, PixelPerfectAngles.defaultSnap(Math.toRadians(70.0)), 1.0E-12);
      assertEquals(Math.toRadians(70.0), PixelPerfectAngles.free(Math.toRadians(70.0)), 1.0E-12);
   }

   @Test
   void selectionSnapSupportsEveryIntegerSlopeUpTo64AndPreservesTurns() {
      for (int x = -64; x <= 64; x++) {
         for (int y = -64; y <= 64; y++) {
            if (x == 0 && y == 0) continue;
            double angle = Math.atan2(y, x);
            assertEquals(angle, PixelPerfectAngles.selectionSnap(angle), 1.0E-12);
         }
      }
      double angle = Math.atan2(1, 64);
      assertEquals(angle, PixelPerfectAngles.selectionSnap(0.0157), 1.0E-12);
      assertEquals(angle + 4 * Math.PI, PixelPerfectAngles.selectionSnap(0.0157 + 4 * Math.PI), 1.0E-12);
      assertEquals(-angle - 4 * Math.PI, PixelPerfectAngles.selectionSnap(-0.0157 - 4 * Math.PI), 1.0E-12);
   }
}
