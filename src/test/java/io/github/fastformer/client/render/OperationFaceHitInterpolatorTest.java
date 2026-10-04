package io.github.fastformer.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.fastplace.geometry.OperationGeometry;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class OperationFaceHitInterpolatorTest {
   @Test
   void snapsAlongFaceNormalButInterpolatesInsideTheFace() {
      OperationFaceHitInterpolator interpolator = new OperationFaceHitInterpolator(50L);
      OperationGeometry.RayHit first = new OperationGeometry.RayHit(Vec3.ZERO, new Vec3(1.0, 0.0, 0.0), 2.0, 0);
      OperationGeometry.RayHit second = new OperationGeometry.RayHit(new Vec3(10.0, 10.0, 0.0), new Vec3(1.0, 0.0, 0.0), 4.0, 0);

      interpolator.update(first, 0L);
      OperationGeometry.RayHit halfway = interpolator.update(second, 25L);

      assertEquals(10.0, halfway.point().x, 0.0001);
      assertEquals(0.0, halfway.point().y, 0.0001);
      halfway = interpolator.update(second, 62L);
      assertEquals(7.4, halfway.point().y, 0.0001);
      halfway = interpolator.update(second, 75L);
      assertEquals(10.0, halfway.point().y, 0.0001);
   }

   @Test
   void settlesWithinTwoTicksAfterContinuousMovementStops() {
      OperationFaceHitInterpolator interpolator = new OperationFaceHitInterpolator(100L);
      for (int time = 0; time <= 200; time += 10) {
         var hit = new OperationGeometry.RayHit(new Vec3(time, time, 0), new Vec3(1, 0, 0), 2, 0);
         var displayed = interpolator.update(hit, time);
         assertEquals(time, displayed.point().x, 0.0001);
         org.junit.jupiter.api.Assertions.assertTrue(displayed.point().y <= time);
      }
      var stopped = new OperationGeometry.RayHit(new Vec3(200, 200, 0), new Vec3(1, 0, 0), 2, 0);
      assertEquals(200, interpolator.update(stopped, 300).point().y, 0.0001);
   }

   @Test
   void interpolatesLinearlyBetweenRecordedPositions() {
      OperationFaceHitInterpolator interpolator = new OperationFaceHitInterpolator(50L);
      interpolator.update(new OperationGeometry.RayHit(Vec3.ZERO, new Vec3(1, 0, 0), 2, 0), 0);
      var target = new OperationGeometry.RayHit(new Vec3(10, 10, 0), new Vec3(1, 0, 0), 4, 0);
      interpolator.update(target, 20);
      OperationGeometry.RayHit halfway = interpolator.update(target, 60);
      assertEquals(8.0, halfway.point().y, 0.0001);
   }

   @Test
   void changingPartsDoesNotMoveTheArrowAcrossUnrelatedFaces() {
      var interpolator = new OperationFaceHitInterpolator(100L);
      var first = new OperationGeometry.RayHit(Vec3.ZERO, new Vec3(1, 0, 0), 2, 0);
      var next = new OperationGeometry.RayHit(new Vec3(0, 20, 0), new Vec3(1, 0, 0), 2, 0);
      interpolator.update(first, 0, 1);
      assertEquals(next, interpolator.update(next, 10, 2));
   }

   @Test
   void snapsWhenTheHoveredFaceChangesAxis() {
      OperationFaceHitInterpolator interpolator = new OperationFaceHitInterpolator(50L);
      OperationGeometry.RayHit first = new OperationGeometry.RayHit(Vec3.ZERO, new Vec3(1.0, 0.0, 0.0), 2.0, 0);
      OperationGeometry.RayHit second = new OperationGeometry.RayHit(new Vec3(10.0, 0.0, 0.0), new Vec3(0.0, 1.0, 0.0), 6.0, 1);

      assertEquals(first, interpolator.update(first, 0L));
      OperationGeometry.RayHit halfway = interpolator.update(second, 25L);
      assertEquals(second.point(), halfway.point());
      assertEquals(second.normal(), halfway.normal());
      assertEquals(second.distance(), halfway.distance(), 0.0001);
      assertEquals(1, halfway.axis());

      assertEquals(second, interpolator.update(second, 75L));
      assertEquals(null, interpolator.update(null, 76L));
      assertEquals(first, interpolator.update(first, 77L));
   }
}
