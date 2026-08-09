package io.github.fastformer.fastplace.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class OperationGeometryTest {
   @Test
   void choosesTheWorldAxisClosestToTheViewRay() {
      Vec3 origin = new Vec3(0.5, 0.5, 0.5);

      assertEquals(0, OperationGeometry.closestWorldAxisToRay(
         origin, new Vec3(0.5, 5.5, 5.5), new Vec3(2.0, -5.0, -5.0)
      ));
      assertEquals(1, OperationGeometry.closestWorldAxisToRay(
         origin, new Vec3(5.5, 0.5, 5.5), new Vec3(-5.0, 2.0, -5.0)
      ));
      assertEquals(2, OperationGeometry.closestWorldAxisToRay(
         origin, new Vec3(5.5, 5.5, 0.5), new Vec3(-5.0, -5.0, 2.0)
      ));
   }

   @Test
   void centerHitUsesTheBestConditionedDragAxisAsTieBreaker() {
      Vec3 origin = new Vec3(0.5, 0.5, 0.5);

      assertEquals(1, OperationGeometry.closestWorldAxisToRay(
         origin, new Vec3(10.5, 0.5, 0.5), new Vec3(-1.0, 0.0, 0.0)
      ));
   }

   @Test
   void excludedPlaneAxisCannotBeSelectedDuringADrag() {
      Vec3 origin = new Vec3(0.5, 0.5, 0.5);

      assertEquals(2, OperationGeometry.closestWorldAxisToRay(
         origin,
         new Vec3(0.5, 10.5, 0.5),
         new Vec3(0.0, -1.0, 0.01),
         1
      ));
   }

   @Test
   void findsTheClosestPointBetweenAViewRayAndFiniteSegment() {
      OperationGeometry.RaySegmentClosest hit = OperationGeometry.closestRaySegment(
         new Vec3(5.5, 8.5, 0.5),
         new Vec3(0.0, -1.0, 0.0),
         new Vec3(0.5, 0.5, 0.5),
         new Vec3(10.5, 0.5, 0.5),
         64.0
      );

      assertEquals(new Vec3(5.5, 0.5, 0.5), hit.segmentPoint());
      assertEquals(8.0, hit.rayDistance(), 1.0E-7);
      assertEquals(0.0, hit.distanceSqr(), 1.0E-7);
   }
}
