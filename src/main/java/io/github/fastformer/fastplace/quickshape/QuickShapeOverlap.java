package io.github.fastformer.fastplace.quickshape;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Detects a candidate that adds no dimension to the confirmed stage. */
public final class QuickShapeOverlap {
   private static final double EPSILON = 1.0E-8;
   private QuickShapeOverlap() { }

   public static boolean overlaps(List<BlockPos> points, BlockPos candidate, FaceMode faceMode, boolean polygonClosed) {
      if (faceMode == FaceMode.POLYGON && !polygonClosed && points.size() >= 3) return false;
      return overlaps(points, candidate);
   }

   public static boolean overlaps(List<BlockPos> points, BlockPos candidate) {
      if (points.isEmpty() || candidate == null) return false;
      if (points.contains(candidate)) return true;
      Vec3 origin = Vec3.atLowerCornerOf(points.getFirst());
      Vec3 delta = Vec3.atLowerCornerOf(candidate).subtract(origin);
      if (points.size() == 1) return false;
      Vec3 edge = null;
      Vec3 normal = null;
      for (BlockPos point : points) {
         Vec3 offset = Vec3.atLowerCornerOf(point).subtract(origin);
         if (offset.lengthSqr() < EPSILON) continue;
         if (edge == null) edge = offset;
         else if (normal == null) {
            Vec3 cross = edge.cross(offset);
            if (cross.lengthSqr() >= EPSILON) normal = cross;
         } else if (Math.abs(normal.dot(offset)) >= EPSILON) return false;
      }
      if (edge == null) return false;
      return normal == null ? edge.cross(delta).lengthSqr() < EPSILON : Math.abs(normal.dot(delta)) < EPSILON;
   }
}
