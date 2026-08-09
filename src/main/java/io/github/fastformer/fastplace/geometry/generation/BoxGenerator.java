package io.github.fastformer.fastplace.geometry.generation;

import io.github.fastformer.fastplace.FillMode;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class BoxGenerator {
   private BoxGenerator() {
   }

   public static Set<BlockPos> generate(PolyhedronParameters parameters, FillMode fillMode, int maxBlocks) {
      if (!parameters.ready()) {
         return controlPoints(parameters);
      }
      PolyhedronGeometry.Bounds bounds = PolyhedronGeometry.bounds(parameters, true);
      double radius = parameters.radius(1.0);
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      for (int x = bounds.minX(); x <= bounds.maxX() && result.size() < maxBlocks; x++) {
         for (int y = bounds.minY(); y <= bounds.maxY() && result.size() < maxBlocks; y++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ() && result.size() < maxBlocks; z++) {
               BlockPos candidate = new BlockPos(x, y, z);
               Vec3 offset = Vec3.atCenterOf(candidate).subtract(parameters.center());
               if (inside(offset, radius, parameters)
                  && (fillMode == FillMode.SOLID || boundary(offset, radius, parameters))) {
                  result.add(candidate);
               }
            }
         }
      }
      return Set.copyOf(result);
   }

   public static long estimateScanCells(PolyhedronParameters parameters) {
      return parameters.ready() ? PolyhedronGeometry.bounds(parameters, true).volume() : parameters.points().size();
   }

   public static Set<BlockPos> previewOutline(PolyhedronParameters parameters, int maxBlocks) {
      if (!parameters.ready() || maxBlocks <= 0) {
         return controlPoints(parameters);
      }
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      double radius = parameters.radius(1.0);
      Vec3[] corners = new Vec3[8];
      for (int index = 0; index < corners.length; index++) {
         Vec3 local = new Vec3(
            (index & 1) == 0 ? -radius : radius,
            (index & 2) == 0 ? -radius : radius,
            (index & 4) == 0 ? -radius : radius
         );
         corners[index] = parameters.center().add(PolyhedronGeometry.toWorld(local, parameters));
      }
      for (int index = 0; index < corners.length && result.size() < maxBlocks; index++) {
         for (int bit = 0; bit < 3; bit++) {
            int other = index ^ (1 << bit);
            if (index < other) {
               LineGenerator.add(result, corners[index], corners[other], maxBlocks);
            }
         }
      }
      return Set.copyOf(result);
   }

   private static boolean inside(Vec3 worldOffset, double radius, PolyhedronParameters parameters) {
      Vec3 local = PolyhedronGeometry.toLocal(worldOffset, parameters);
      return Math.max(Math.abs(local.x), Math.max(Math.abs(local.y), Math.abs(local.z))) <= radius;
   }

   private static boolean boundary(Vec3 point, double radius, PolyhedronParameters parameters) {
      return !inside(point.add(1.0, 0.0, 0.0), radius, parameters)
         || !inside(point.add(-1.0, 0.0, 0.0), radius, parameters)
         || !inside(point.add(0.0, 1.0, 0.0), radius, parameters)
         || !inside(point.add(0.0, -1.0, 0.0), radius, parameters)
         || !inside(point.add(0.0, 0.0, 1.0), radius, parameters)
         || !inside(point.add(0.0, 0.0, -1.0), radius, parameters);
   }

   private static Set<BlockPos> controlPoints(PolyhedronParameters parameters) {
      return parameters.points().stream()
         .map(BlockPos::containing)
         .collect(java.util.stream.Collectors.toUnmodifiableSet());
   }
}
