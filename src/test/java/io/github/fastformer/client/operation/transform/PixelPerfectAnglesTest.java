package io.github.fastformer.client.operation.transform;

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
}
