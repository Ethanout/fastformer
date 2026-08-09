package io.github.fastformer.fastplace.geometry.generation;

import io.github.fastformer.fastplace.FaceMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class PlanarFaceGeometry {
   private PlanarFaceGeometry() {
   }

   public static List<Vec3> vertices(List<BlockPos> points, FaceMode mode) {
      if (mode == FaceMode.POLYGON) {
         return points.stream().map(Vec3::atCenterOf).toList();
      }
      Vec3 first = Vec3.atCenterOf(points.getFirst());
      Vec3 second = Vec3.atCenterOf(points.get(1));
      Vec3 third = Vec3.atCenterOf(points.get(2));
      if (mode == FaceMode.PARALLELOGRAM_BASE_PLANE) {
         return List.of(first, second, third, first.add(third.subtract(second)));
      }
      Vec3 offset = third.subtract(first);
      return List.of(first, second, second.add(offset), third);
   }

   public static Set<BlockPos> outline(List<Vec3> vertices, int maxBlocks) {
      return outline(vertices, maxBlocks, LineTieBias.DEFAULT);
   }

   public static Set<BlockPos> outline(List<Vec3> vertices, int maxBlocks, LineTieBias tieBias) {
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      for (int index = 0; index < vertices.size() && result.size() < maxBlocks; index++) {
         LineGenerator.add(result, vertices.get(index), vertices.get((index + 1) % vertices.size()), maxBlocks, tieBias);
      }
      return result;
   }

   public static Vec3 normal(List<Vec3> vertices) {
      if (vertices.size() < 3) {
         return Vec3.ZERO;
      }
      Vec3 origin = vertices.getFirst();
      for (int first = 1; first < vertices.size() - 1; first++) {
         for (int second = first + 1; second < vertices.size(); second++) {
            Vec3 normal = vertices.get(first).subtract(origin).cross(vertices.get(second).subtract(origin));
            if (normal.lengthSqr() >= 1.0E-7) {
               return normal.normalize();
            }
         }
      }
      return Vec3.ZERO;
   }
}
