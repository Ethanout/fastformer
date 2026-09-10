package io.github.fastformer.fastplace.geometry.generation;

import io.github.fastformer.fastplace.FillMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class PyramidGenerator {
   private PyramidGenerator() {
   }

   public static Set<BlockPos> generate(List<Vec3> base, Vec3 extrusion, FillMode fillMode, int maxBlocks) {
      return generate(base, extrusion, fillMode, maxBlocks, BlockGenerationObserver.NONE);
   }

   public static Set<BlockPos> generate(
      List<Vec3> base, Vec3 extrusion, FillMode fillMode, int maxBlocks, BlockGenerationObserver observer
   ) {
      Vec3 center = center(base);
      Vec3 normal = PlanarFaceGeometry.normal(base);
      double height = extrusion.dot(normal);
      Vec3 apex = center.add(normal.scale(height));
      int stagingLimit = GenerationLimitExceeded.probeLimit(maxBlocks);
      Set<BlockPos> result = new ObservedBlockSet(observer);
      if (fillMode == FillMode.OUTLINE) {
         result.addAll(PlanarFaceGeometry.outline(base, stagingLimit));
         for (Vec3 vertex : base) {
            LineGenerator.add(result, vertex, apex, stagingLimit);
         }
         if (result.size() < stagingLimit) {
            result.add(BlockPos.containing(apex));
         }
         return GenerationLimitExceeded.boundedResult(result, maxBlocks, observer);
      }

      int layers = Math.max(
         1,
         (int)Math.ceil(Math.max(Math.abs(normal.x * height), Math.max(Math.abs(normal.y * height), Math.abs(normal.z * height))))
      );
      for (int layer = 0; layer <= layers && result.size() < stagingLimit; layer++) {
         double ratio = (double)layer / (double)layers;
         Vec3 offset = normal.scale(height * ratio);
         double scale = 1.0 - ratio;
         List<Vec3> section = base.stream()
            .map(vertex -> center.add(vertex.subtract(center).scale(scale)).add(offset))
            .toList();
         Set<BlockPos> sectionBlocks = fillMode == FillMode.SOLID
            ? PolygonFaceGenerator.generate(section, FillMode.SOLID, stagingLimit)
            : PlanarFaceGeometry.outline(section, stagingLimit);
         if (GenerationLimitExceeded.is(sectionBlocks)) {
            return GenerationLimitExceeded.witness(maxBlocks, observer);
         }
         addUntilLimit(result, sectionBlocks, stagingLimit);
      }
      if (fillMode == FillMode.HOLLOW && result.size() < stagingLimit) {
         Set<BlockPos> baseBlocks = PolygonFaceGenerator.generate(base, FillMode.SOLID, stagingLimit);
         if (GenerationLimitExceeded.is(baseBlocks)) {
            return GenerationLimitExceeded.witness(maxBlocks, observer);
         }
         addUntilLimit(result, baseBlocks, stagingLimit);
      }
      if (result.size() < stagingLimit) {
         result.add(BlockPos.containing(apex));
      }
      return GenerationLimitExceeded.boundedResult(result, maxBlocks, observer);
   }

   private static Vec3 center(List<Vec3> vertices) {
      Vec3 sum = Vec3.ZERO;
      for (Vec3 vertex : vertices) {
         sum = sum.add(vertex);
      }
      return vertices.isEmpty() ? Vec3.ZERO : sum.scale(1.0 / vertices.size());
   }

   private static void addUntilLimit(Set<BlockPos> output, Set<BlockPos> source, int maxBlocks) {
      for (BlockPos position : source) {
         output.add(position);
         if (output.size() >= maxBlocks) {
            return;
         }
      }
   }
}
