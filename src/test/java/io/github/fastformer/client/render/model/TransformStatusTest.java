package io.github.fastformer.client.render.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class TransformStatusTest {
   private static final double TOLERANCE = 1.0E-9;

   @Test
   void relativeStatusUsesTranslationScaleRatioAndWrappedRotation() {
      TransformStatus baseline = new TransformStatus(
         new Vec3(10.0, 20.0, 30.0),
         new Vec3(2.0, 4.0, 8.0),
         new Vec3(10.0, 350.0, -170.0)
      );
      TransformStatus current = new TransformStatus(
         new Vec3(13.0, 18.0, 35.0),
         new Vec3(4.0, 2.0, 8.0),
         new Vec3(370.0, 10.0, 170.0)
      );

      TransformStatus relative = current.relativeTo(baseline);

      assertVecEquals(new Vec3(3.0, -2.0, 5.0), relative.position());
      assertVecEquals(new Vec3(2.0, 0.5, 1.0), relative.scale());
      assertVecEquals(new Vec3(0.0, 20.0, -20.0), relative.rotationDegrees());
   }

   @Test
   void identityMatrixHasZeroEulerRotation() {
      Vec3 rotation = TransformStatus.eulerDegrees(new double[] {
         1.0, 0.0, 0.0,
         0.0, 1.0, 0.0,
         0.0, 0.0, 1.0
      });

      assertVecEquals(Vec3.ZERO, rotation);
   }

   private static void assertVecEquals(Vec3 expected, Vec3 actual) {
      assertEquals(expected.x, actual.x, TOLERANCE);
      assertEquals(expected.y, actual.y, TOLERANCE);
      assertEquals(expected.z, actual.z, TOLERANCE);
   }
}
