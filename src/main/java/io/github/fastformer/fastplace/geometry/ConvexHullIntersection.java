package io.github.fastformer.fastplace.geometry;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Clips a box against the same half spaces used by hull containment. */
public final class ConvexHullIntersection {
   private static final double EPSILON = 1.0E-7;
   private ConvexHullIntersection() { }

   public static boolean intersects(AABB box, List<OperationGeometry.HullFace> hull, int inflation) {
      Vec3[] v = new Vec3[8];
      for (int i = 0; i < 8; i++) v[i] = new Vec3((i & 1) == 0 ? box.minX : box.maxX,
         (i & 2) == 0 ? box.minY : box.maxY, (i & 4) == 0 ? box.minZ : box.maxZ);
      List<List<Vec3>> polygons = new ArrayList<>();
      for (int[] face : new int[][] {{0,2,6,4},{1,3,7,5},{0,1,5,4},{2,3,7,6},{0,1,3,2},{4,5,7,6}})
         polygons.add(List.of(v[face[0]], v[face[1]], v[face[2]], v[face[3]]));
      for (var plane : hull) {
         List<List<Vec3>> clipped = new ArrayList<>();
         List<Vec3> cap = new ArrayList<>();
         for (List<Vec3> polygon : polygons) {
            List<Vec3> output = new ArrayList<>();
            Vec3 previous = polygon.getLast();
            double previousDistance = distance(previous, plane, inflation);
            for (Vec3 current : polygon) {
               double currentDistance = distance(current, plane, inflation);
               if ((currentDistance <= EPSILON) != (previousDistance <= EPSILON)) {
                  Vec3 point = previous.lerp(current, previousDistance / (previousDistance - currentDistance));
                  output.add(point);
                  if (cap.stream().noneMatch(existing -> existing.distanceToSqr(point) < EPSILON * EPSILON)) cap.add(point);
               }
               if (currentDistance <= EPSILON) output.add(current);
               previous = current;
               previousDistance = currentDistance;
            }
            if (!output.isEmpty()) clipped.add(output);
         }
         if (clipped.isEmpty()) return false;
         if (cap.size() >= 3) {
            Vec3 center = cap.stream().reduce(Vec3.ZERO, Vec3::add).scale(1.0 / cap.size());
            Vec3 u = cap.getFirst().subtract(center).normalize();
            Vec3 w = plane.normal().cross(u);
            cap.sort(java.util.Comparator.comparingDouble(point -> Math.atan2(point.subtract(center).dot(w), point.subtract(center).dot(u))));
            clipped.add(cap);
         }
         polygons = clipped;
      }
      return true;
   }

   private static double distance(Vec3 point, OperationGeometry.HullFace face, int inflation) {
      return point.subtract(face.a()).dot(face.normal()) - Math.max(-128, inflation);
   }
}
