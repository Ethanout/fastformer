package io.github.fastformer.fastplace.geometry;

import java.util.Optional;
import net.minecraft.world.phys.Vec3;

public final class GeometryConstraints {
   private static final double EPSILON = 1.0E-7;

   private GeometryConstraints() {
   }

   public static Optional<Plane> planeFromPoints(Vec3 first, Vec3 second, Vec3 third) {
      Vec3 normal = second.subtract(first).cross(third.subtract(first));
      return normal.lengthSqr() < EPSILON ? Optional.empty() : Optional.of(new Plane(first, normal));
   }

   public static Optional<Vec3> rayPlane(Vec3 rayOrigin, Vec3 rayDirection, Plane plane) {
      return rayPlane(rayOrigin, rayDirection, plane.point(), plane.normal());
   }

   public static Optional<Vec3> rayPlane(Vec3 rayOrigin, Vec3 rayDirection, Vec3 planePoint, Vec3 planeNormal) {
      Vec3 direction = normalize(rayDirection);
      if (direction.lengthSqr() < EPSILON || planeNormal.lengthSqr() < EPSILON) {
         return Optional.empty();
      }
      double denominator = planeNormal.dot(direction);
      if (Math.abs(denominator) < EPSILON) {
         return Optional.empty();
      }
      double distance = planeNormal.dot(planePoint.subtract(rayOrigin)) / denominator;
      return distance < 0.0 ? Optional.empty() : Optional.of(rayOrigin.add(direction.scale(distance)));
   }

   public static double rayAxisOffset(Vec3 rayOrigin, Vec3 rayDirection, Vec3 axisOrigin, Vec3 axisDirection, double parallelFallbackDistance) {
      return rayAxisOffset(
         rayOrigin,
         rayDirection,
         axisOrigin,
         axisDirection,
         parallelFallbackDistance,
         Double.POSITIVE_INFINITY
      );
   }

   public static double rayAxisOffset(
      Vec3 rayOrigin,
      Vec3 rayDirection,
      Vec3 axisOrigin,
      Vec3 axisDirection,
      double parallelFallbackDistance,
      double maxRayDistance
   ) {
      Vec3 axis = normalize(axisDirection);
      if (axis.lengthSqr() < EPSILON) {
         return 0.0;
      }
      Vec3 direction = normalize(rayDirection);
      if (direction.lengthSqr() < EPSILON) {
         direction = new Vec3(0.0, 0.0, 1.0);
      }
      Vec3 originFromAxis = rayOrigin.subtract(axisOrigin);
      double originAxisOffset = originFromAxis.dot(axis);
      double parallel = direction.dot(axis);
      double denominator = 1.0 - parallel * parallel;
      if (denominator < EPSILON) {
         if (Double.isFinite(maxRayDistance)) {
            double rayDistance = Math.clamp(Math.max(0.0, parallelFallbackDistance), 0.0, Math.max(0.0, maxRayDistance));
            return originAxisOffset + parallel * rayDistance;
         }
         double fallback = Math.max(Math.max(0.0, parallelFallbackDistance), Math.abs(originAxisOffset));
         return originAxisOffset + Math.copySign(fallback, parallel == 0.0 ? 1.0 : parallel);
      }
      double rayDistance = (parallel * originAxisOffset - direction.dot(originFromAxis)) / denominator;
      double limit = Double.isFinite(maxRayDistance) ? Math.max(0.0, maxRayDistance) : Double.POSITIVE_INFINITY;
      rayDistance = Math.clamp(rayDistance, 0.0, limit);
      return originAxisOffset + parallel * rayDistance;
   }

   private static Vec3 normalize(Vec3 value) {
      return value.lengthSqr() < EPSILON ? Vec3.ZERO : value.normalize();
   }

   public record Plane(Vec3 point, Vec3 normal) {
   }
}
