package io.github.fastformer.fastplace.geometry.generation;

import io.github.fastformer.fastplace.FillMode;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class ConePrismGenerator {
   private static final double VOXEL_CENTER_ERROR = Math.sqrt(3.0) * 0.5;
   private ConePrismGenerator() {
   }

   public static Set<BlockPos> generate(ConePrismParameters parameters, FillMode fillMode, int maxBlocks) {
      ConePrismGeometry geometry = ConePrismGeometry.from(parameters);
      if (maxBlocks <= 0) {
         return GenerationLimitExceeded.witness(maxBlocks);
      }
      if (geometry == null || !geometry.heightReady()) {
         return GenerationLimitExceeded.boundedResult(
            controlPoints(parameters), maxBlocks, BlockGenerationObserver.NONE
         );
      }
      return fillMode == FillMode.OUTLINE
         ? generateOutline(parameters, maxBlocks)
         : generateVolume(geometry, fillMode == FillMode.HOLLOW, maxBlocks);
   }

   public static BlockGenerationResult generateResult(ConePrismParameters parameters, FillMode fillMode, int maxBlocks) {
      if (parameters == null) {
         return BlockGenerationResult.constraintsFailed();
      }
      ConePrismGeometry geometry = ConePrismGeometry.from(parameters);
      if (geometry == null || !geometry.heightReady()) {
         return BlockGenerationResult.constraintsFailed();
      }
      return BlockGenerationResult.fromLegacy(generate(parameters, fillMode, maxBlocks));
   }

   private static Set<BlockPos> generateOutline(ConePrismParameters parameters, int maxBlocks) {
      Set<BlockPos> outline = previewOutline(parameters, GenerationLimitExceeded.probeLimit(maxBlocks));
      return GenerationLimitExceeded.boundedResult(outline, maxBlocks, BlockGenerationObserver.NONE);
   }

   private static Set<BlockPos> generateVolume(ConePrismGeometry geometry, boolean boundaryOnly, int maxBlocks) {
      ConePrismGeometry.VoxelBounds bounds = geometry.voxelBounds();
      long count = countVolume(geometry, bounds, boundaryOnly);
      if (count > maxBlocks) {
         return GenerationLimitExceeded.witness(maxBlocks);
      }
      return new LazyBlockSet(
         bounds.minX(), bounds.minY(), bounds.minZ(),
         bounds.maxX(), bounds.maxY(), bounds.maxZ(), (int)count,
         (x, y, z) -> geometry.contains(x + 0.5, y + 0.5, z + 0.5)
            && (!boundaryOnly || geometry.isBoundary(x + 0.5, y + 0.5, z + 0.5))
      );
   }

   private static long countVolume(
      ConePrismGeometry geometry, ConePrismGeometry.VoxelBounds bounds, boolean boundaryOnly
   ) {
      long count = 0L;
      for (long x = bounds.minX(); x <= (long)bounds.maxX(); x++) {
         for (long y = bounds.minY(); y <= (long)bounds.maxY(); y++) {
            for (long z = bounds.minZ(); z <= (long)bounds.maxZ(); z++) {
               if (geometry.contains(x + 0.5, y + 0.5, z + 0.5)
                  && (!boundaryOnly || geometry.isBoundary(x + 0.5, y + 0.5, z + 0.5))) {
                  count = count == Long.MAX_VALUE ? count : count + 1L;
               }
            }
         }
      }
      return count;
   }

   public static Set<BlockPos> baseOutline(ConePrismParameters parameters, int maxBlocks) {
      ConePrismGeometry geometry = ConePrismGeometry.from(parameters);
      if (geometry == null) {
         return controlPoints(parameters);
      }
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      addRing(result, geometry, geometry.layer(0), maxBlocks);
      return Set.copyOf(result);
   }

   public static Set<BlockPos> previewOutline(ConePrismParameters parameters, int maxBlocks) {
      ConePrismGeometry geometry = ConePrismGeometry.from(parameters);
      if (geometry == null || maxBlocks <= 0) {
         return controlPoints(parameters);
      }
      ConePrismGeometry.Base base = geometry.base();
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      addEllipseRing(
         result,
         base.center(),
         base.axisU(),
         base.axisV(),
         base.radius() * geometry.scaleX(),
         base.radius() * geometry.scaleZ(),
         maxBlocks
      );
      if (!geometry.heightReady() || result.size() >= maxBlocks) {
         return Set.copyOf(result);
      }

      Vec3 topCenter = geometry.topCenter();
      double topRadiusU = base.radius() * geometry.scaleX() * geometry.topScale();
      double topRadiusV = base.radius() * geometry.scaleZ() * geometry.topScale();
      addEllipseRing(result, topCenter, base.axisU(), base.axisV(), topRadiusU, topRadiusV, maxBlocks);
      for (int index = 0; index < 8 && result.size() < maxBlocks; index++) {
         double angle = Math.PI * 2.0 * (double)index / 8.0;
         Vec3 bottom = ellipsePoint(
            base.center(), base.axisU(), base.axisV(), base.radius() * geometry.scaleX(), base.radius() * geometry.scaleZ(), angle
         );
         Vec3 top = ellipsePoint(topCenter, base.axisU(), base.axisV(), topRadiusU, topRadiusV, angle);
         LineGenerator.add(result, bottom, top, maxBlocks);
      }
      return Set.copyOf(result);
   }

   public static long estimateScanCells(ConePrismParameters parameters) {
      ConePrismGeometry geometry = ConePrismGeometry.from(parameters);
      if (geometry == null) {
         return parameters.points().size();
      }
      return geometry.voxelBounds().volume();
   }

   public static ConePrismGeometry.Base baseInfo(ConePrismParameters parameters) {
      ConePrismGeometry geometry = ConePrismGeometry.from(parameters);
      return geometry == null ? null : geometry.base();
   }

   private static void addRing(
      Set<BlockPos> result, ConePrismGeometry geometry, ConePrismGeometry.Layer layer, int maxBlocks
   ) {
      int extentX = layerExtent(layer.radius(), geometry.scaleX());
      for (int x = -extentX; x <= extentX && result.size() < maxBlocks; x++) {
         int extentZ = rowExtent(x, layer.radius(), geometry.scaleX(), geometry.scaleZ());
         for (int z = -extentZ; z <= extentZ && result.size() < maxBlocks; z++) {
            BlockPos candidate = candidate(geometry.base(), layer.center(), x, z);
            Vec3 local = Vec3.atCenterOf(candidate).subtract(layer.center());
            double localX = local.dot(geometry.base().axisU());
            double localZ = local.dot(geometry.base().axisV());
            if (layerInside(localX, localZ, layer.radius(), geometry.scaleX(), geometry.scaleZ())
               && layerBoundary(localX, localZ, layer.radius(), geometry.scaleX(), geometry.scaleZ())) {
               result.add(candidate);
            }
         }
      }
   }

   private static BlockPos candidate(ConePrismGeometry.Base base, Vec3 center, int x, int z) {
      return BlockPos.containing(center.add(base.axisU().scale(x)).add(base.axisV().scale(z)));
   }

   private static int layerExtent(double radius, double scale) {
      return (int)Math.ceil((radius + 0.25) * scale + VOXEL_CENTER_ERROR);
   }

   private static int rowExtent(int x, double radius, double scaleX, double scaleZ) {
      double outerRadius = radius + 0.25;
      double minimumLocalX = Math.max(0.0, Math.abs(x) - VOXEL_CENTER_ERROR);
      double normalizedX = minimumLocalX / scaleX;
      double remaining = outerRadius * outerRadius - normalizedX * normalizedX;
      return remaining < 0.0
         ? 0
         : (int)Math.ceil(Math.sqrt(remaining) * scaleZ + VOXEL_CENTER_ERROR);
   }

   private static boolean layerInside(double x, double z, double radius, double scaleX, double scaleZ) {
      return Math.hypot(x / scaleX, z / scaleZ) <= radius + 0.25;
   }

   private static boolean layerBoundary(double x, double z, double radius, double scaleX, double scaleZ) {
      return !layerInside(x + 1.0, z, radius, scaleX, scaleZ)
         || !layerInside(x - 1.0, z, radius, scaleX, scaleZ)
         || !layerInside(x, z + 1.0, radius, scaleX, scaleZ)
         || !layerInside(x, z - 1.0, radius, scaleX, scaleZ);
   }

   private static void addEllipseRing(
      Set<BlockPos> output,
      Vec3 center,
      Vec3 axisU,
      Vec3 axisV,
      double radiusU,
      double radiusV,
      int maxBlocks
   ) {
      double maxRadius = Math.max(Math.abs(radiusU), Math.abs(radiusV));
      if (maxRadius < 1.0E-7) {
         output.add(BlockPos.containing(center));
         return;
      }
      int segments = Math.clamp((int)Math.ceil(Math.PI * 4.0 * maxRadius), 16, 2048);
      Vec3 previous = ellipsePoint(center, axisU, axisV, radiusU, radiusV, 0.0);
      for (int segment = 1; segment <= segments && output.size() < maxBlocks; segment++) {
         double angle = Math.PI * 2.0 * (double)segment / (double)segments;
         Vec3 next = ellipsePoint(center, axisU, axisV, radiusU, radiusV, angle);
         LineGenerator.add(output, previous, next, maxBlocks);
         previous = next;
      }
   }

   private static Vec3 ellipsePoint(
      Vec3 center, Vec3 axisU, Vec3 axisV, double radiusU, double radiusV, double angle
   ) {
      return center.add(axisU.scale(Math.cos(angle) * radiusU)).add(axisV.scale(Math.sin(angle) * radiusV));
   }

   private static Set<BlockPos> controlPoints(ConePrismParameters parameters) {
      return parameters.points().stream()
         .map(BlockPos::containing)
         .collect(java.util.stream.Collectors.toUnmodifiableSet());
   }
}
