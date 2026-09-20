package io.github.fastformer.fastplace.geometry;

import io.github.fastformer.fastplace.selection.OperationStackRegion;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class OperationGeometry {
   private static final double EPSILON = 1.0E-7;

   private OperationGeometry() {
   }

   public static AABB bounds(List<BlockPos> points) {
      return bounds(points, BlockPos.ZERO, BlockPos.ZERO);
   }

   public static AABB bounds(List<BlockPos> points, BlockPos minOffset, BlockPos maxOffset) {
      return bounds(points, minOffset, maxOffset, 0);
   }

   public static AABB bounds(List<BlockPos> points, BlockPos minOffset, BlockPos maxOffset, int hullInflation) {
      if (points.isEmpty()) {
         return null;
      }

      int inflation = Math.max(0, hullInflation);
      int minX = points.stream().mapToInt(BlockPos::getX).min().orElse(0) + minOffset.getX() - inflation;
      int minY = points.stream().mapToInt(BlockPos::getY).min().orElse(0) + minOffset.getY() - inflation;
      int minZ = points.stream().mapToInt(BlockPos::getZ).min().orElse(0) + minOffset.getZ() - inflation;
      int maxX = points.stream().mapToInt(BlockPos::getX).max().orElse(0) + maxOffset.getX() + inflation;
      int maxY = points.stream().mapToInt(BlockPos::getY).max().orElse(0) + maxOffset.getY() + inflation;
      int maxZ = points.stream().mapToInt(BlockPos::getZ).max().orElse(0) + maxOffset.getZ() + inflation;
      if (minX > maxX || minY > maxY || minZ > maxZ) {
         return null;
      }
      return new AABB(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0);
   }

   public static Vec3 closestPointOnAxisToRay(Vec3 axisOrigin, Vec3 axis, Vec3 eye, Vec3 view) {
      Vec3 localAxis = normalize(axis);
      Vec3 localView = normalize(view);
      double parallel = localAxis.dot(localView);
      double denominator = 1.0 - parallel * parallel;
      Vec3 eyeFromAxis = eye.subtract(axisOrigin);
      if (denominator < EPSILON) {
         return axisOrigin.add(localAxis.scale(eyeFromAxis.dot(localAxis)));
      }

      double rayDistance = (parallel * localAxis.dot(eyeFromAxis) - localView.dot(eyeFromAxis)) / denominator;
      Vec3 rayPoint = eye.add(localView.scale(Math.max(0.0, rayDistance)));
      return axisOrigin.add(localAxis.scale(rayPoint.subtract(axisOrigin).dot(localAxis)));
   }

   public static int closestWorldAxisToRay(Vec3 axisOrigin, Vec3 eye, Vec3 view) {
      return closestWorldAxisToRay(axisOrigin, eye, view, -1);
   }

   public static int closestWorldAxisToRay(Vec3 axisOrigin, Vec3 eye, Vec3 view, int excludedAxis) {
      if (axisOrigin == null || eye == null || view == null) {
         return -1;
      }
      Vec3 ray = normalize(view);
      if (ray.lengthSqr() < EPSILON) {
         return -1;
      }
      Vec3[] axes = {
         new Vec3(1.0, 0.0, 0.0),
         new Vec3(0.0, 1.0, 0.0),
         new Vec3(0.0, 0.0, 1.0)
      };
      int bestAxis = -1;
      double bestDistance = Double.POSITIVE_INFINITY;
      double bestConditioning = Double.NEGATIVE_INFINITY;
      for (int axis = 0; axis < axes.length; axis++) {
         if (axis == excludedAxis) {
            continue;
         }
         Vec3 axisPoint = closestPointOnAxisToRay(axisOrigin, axes[axis], eye, ray);
         double rayDistance = Math.max(0.0, axisPoint.subtract(eye).dot(ray));
         double distance = axisPoint.distanceToSqr(eye.add(ray.scale(rayDistance)));
         double conditioning = 1.0 - Math.abs(axes[axis].dot(ray));
         if (distance < bestDistance - EPSILON
            || Math.abs(distance - bestDistance) <= EPSILON && conditioning > bestConditioning + EPSILON) {
            bestAxis = axis;
            bestDistance = distance;
            bestConditioning = conditioning;
         }
      }
      return bestAxis;
   }

   public static RaySegmentClosest closestRaySegment(
      Vec3 eye, Vec3 view, Vec3 segmentStart, Vec3 segmentEnd, double maxRayDistance
   ) {
      if (eye == null || view == null || segmentStart == null || segmentEnd == null) {
         return null;
      }
      Vec3 ray = normalize(view);
      Vec3 segment = segmentEnd.subtract(segmentStart);
      double segmentLengthSqr = segment.lengthSqr();
      if (ray.lengthSqr() < EPSILON || segmentLengthSqr < EPSILON) {
         return null;
      }

      Vec3 fromSegment = eye.subtract(segmentStart);
      double raySegment = ray.dot(segment);
      double rayOffset = ray.dot(fromSegment);
      double segmentOffset = segment.dot(fromSegment);
      double denominator = segmentLengthSqr - raySegment * raySegment;
      double segmentParameter = Math.abs(denominator) < EPSILON
         ? Math.clamp(segmentOffset / segmentLengthSqr, 0.0, 1.0)
         : Math.clamp((segmentOffset - raySegment * rayOffset) / denominator, 0.0, 1.0);
      double rayDistance = Math.max(0.0, raySegment * segmentParameter - rayOffset);
      segmentParameter = Math.clamp(
         (segmentOffset + raySegment * rayDistance) / segmentLengthSqr, 0.0, 1.0
      );
      rayDistance = Math.max(0.0, raySegment * segmentParameter - rayOffset);
      if (!Double.isFinite(maxRayDistance) || maxRayDistance < 0.0 || rayDistance > maxRayDistance) {
         return null;
      }
      Vec3 rayPoint = eye.add(ray.scale(rayDistance));
      Vec3 segmentPoint = segmentStart.add(segment.scale(segmentParameter));
      return new RaySegmentClosest(rayPoint, segmentPoint, rayDistance, rayPoint.distanceToSqr(segmentPoint));
   }

   public static BlockPos viewAxisStep(Vec3 view, int steps) {
      Direction direction = Direction.getNearest(view.x, view.y, view.z);
      return BlockPos.ZERO.relative(direction, steps);
   }

   public static BlockPos stackDisplacement(AABB bounds, BlockPos repetition) {
      int width = (int)Math.round(bounds.getXsize());
      int height = (int)Math.round(bounds.getYsize());
      int depth = (int)Math.round(bounds.getZsize());
      return new BlockPos(repetition.getX() * width, repetition.getY() * height, repetition.getZ() * depth);
   }

   public static List<BlockPos> stackTargets(
      AABB bounds, OperationStackRegion region, BlockPos translation, int limit
   ) {
      if (bounds == null || region == null || translation == null || limit <= 0) {
         return List.of();
      }
      return region.repetitions(limit).stream()
         .map(repetition -> stackDisplacement(bounds, repetition).offset(translation))
         .toList();
   }

   public static List<BlockPos> stackRepetitions(BlockPos vector, int limit) {
      java.util.ArrayList<BlockPos> repetitions = new java.util.ArrayList<>();
      int minX = Math.min(0, vector.getX());
      int maxX = Math.max(0, vector.getX());
      int minY = Math.min(0, vector.getY());
      int maxY = Math.max(0, vector.getY());
      int minZ = Math.min(0, vector.getZ());
      int maxZ = Math.max(0, vector.getZ());
      for (int x = minX; x <= maxX && repetitions.size() < limit; x++) {
         for (int y = minY; y <= maxY && repetitions.size() < limit; y++) {
            for (int z = minZ; z <= maxZ && repetitions.size() < limit; z++) {
               if (x != 0 || y != 0 || z != 0) {
                  repetitions.add(new BlockPos(x, y, z));
               }
            }
         }
      }
      return List.copyOf(repetitions);
   }

   public static long stackRepetitionCount(BlockPos vector) {
      return (long)(Math.abs(vector.getX()) + 1)
         * (long)(Math.abs(vector.getY()) + 1)
         * (long)(Math.abs(vector.getZ()) + 1)
         - 1L;
   }

   public static List<HullFace> convexHullFaces(List<BlockPos> points) {
      if (points.size() < 4) {
         return List.of();
      }
      List<Vec3> vertices = points.stream().map(Vec3::atCenterOf).toList();
      java.util.ArrayList<HullFace> faces = new java.util.ArrayList<>();
      for (int i = 0; i < vertices.size() - 2; i++) {
         for (int j = i + 1; j < vertices.size() - 1; j++) {
            for (int k = j + 1; k < vertices.size(); k++) {
               Vec3 a = vertices.get(i);
               Vec3 b = vertices.get(j);
               Vec3 c = vertices.get(k);
               Vec3 normal = b.subtract(a).cross(c.subtract(a));
               if (normal.lengthSqr() < EPSILON) {
                  continue;
               }
               boolean positive = false;
               boolean negative = false;
               for (Vec3 vertex : vertices) {
                  double side = vertex.subtract(a).dot(normal);
                  positive |= side > EPSILON;
                  negative |= side < -EPSILON;
               }
               if (positive && negative) {
                  continue;
               }
               if (positive) {
                  normal = normal.scale(-1.0);
               }
               faces.add(new HullFace(a, b, c, normalize(normal)));
            }
         }
      }
      return List.copyOf(faces);
   }

   public static boolean insideConvexHull(Vec3 point, List<HullFace> faces) {
      return insideConvexHull(point, faces, 0);
   }

   public static boolean insideConvexHull(Vec3 point, List<HullFace> faces, int inflation) {
      if (faces.isEmpty()) {
         return true;
      }
      double limit = Math.max(-128.0, inflation);
      for (HullFace face : faces) {
         if (point.subtract(face.a()).dot(face.normal()) > limit + EPSILON) {
            return false;
         }
      }
      return true;
   }

   public static RayHit raycast(AABB bounds, Vec3 origin, Vec3 direction, double maxDistance) {
      if (bounds == null) {
         return null;
      }

      Vec3 ray = normalize(direction);
      if (ray.lengthSqr() < EPSILON) {
         return null;
      }

      double near = 0.0;
      double far = maxDistance;
      Vec3 nearNormal = Vec3.ZERO;
      Vec3 farNormal = Vec3.ZERO;

      AxisHit x = intersectAxis(origin.x, ray.x, bounds.minX, bounds.maxX, new Vec3(-1.0, 0.0, 0.0), new Vec3(1.0, 0.0, 0.0));
      AxisHit y = intersectAxis(origin.y, ray.y, bounds.minY, bounds.maxY, new Vec3(0.0, -1.0, 0.0), new Vec3(0.0, 1.0, 0.0));
      AxisHit z = intersectAxis(origin.z, ray.z, bounds.minZ, bounds.maxZ, new Vec3(0.0, 0.0, -1.0), new Vec3(0.0, 0.0, 1.0));
      if (x == null || y == null || z == null) {
         return null;
      }

      for (AxisHit hit : List.of(x, y, z)) {
         if (hit.near() > near) {
            near = hit.near();
            nearNormal = hit.nearNormal();
         }
         if (hit.far() < far) {
            far = hit.far();
            farNormal = hit.farNormal();
         }
         if (near > far) {
            return null;
         }
      }

      boolean inside = bounds.contains(origin);
      double distance = inside ? far : near;
      Vec3 normal = inside ? farNormal : nearNormal;
      return distance < 0.0 || distance > maxDistance
         ? null
         : new RayHit(origin.add(ray.scale(distance)), normal, distance, normalAxis(normal));
   }

   private static AxisHit intersectAxis(double origin, double direction, double min, double max, Vec3 minNormal, Vec3 maxNormal) {
      if (Math.abs(direction) < EPSILON) {
         return origin < min || origin > max ? null : new AxisHit(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Vec3.ZERO, Vec3.ZERO);
      }

      double first = (min - origin) / direction;
      double second = (max - origin) / direction;
      if (first <= second) {
         return new AxisHit(first, second, minNormal, maxNormal);
      }
      return new AxisHit(second, first, maxNormal, minNormal);
   }

   private static Vec3 normalize(Vec3 vector) {
      double length = vector.length();
      return length < EPSILON ? Vec3.ZERO : vector.scale(1.0 / length);
   }

   private static int normalAxis(Vec3 normal) {
      double x = Math.abs(normal.x);
      double y = Math.abs(normal.y);
      double z = Math.abs(normal.z);
      return x >= y && x >= z ? 0 : y >= z ? 1 : 2;
   }

   private record AxisHit(double near, double far, Vec3 nearNormal, Vec3 farNormal) {
   }

   public record RayHit(Vec3 point, Vec3 normal, double distance, int axis) {
   }

   public record RaySegmentClosest(Vec3 rayPoint, Vec3 segmentPoint, double rayDistance, double distanceSqr) {
   }

   public record HullFace(Vec3 a, Vec3 b, Vec3 c, Vec3 normal) {
   }
}
