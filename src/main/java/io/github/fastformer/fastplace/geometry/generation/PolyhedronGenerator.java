package io.github.fastformer.fastplace.geometry.generation;

import io.github.fastformer.fastplace.FillMode;
import java.util.Set;
import net.minecraft.core.BlockPos;

public final class PolyhedronGenerator {
   private PolyhedronGenerator() {
   }

   public static Set<BlockPos> generate(PolyhedronParameters parameters, FillMode fillMode, int maxBlocks) {
      return SphereGenerator.generate(parameters, fillMode, maxBlocks);
   }

   public static long estimateScanCells(PolyhedronParameters parameters) {
      return SphereGenerator.estimateScanCells(parameters);
   }

   public static Set<BlockPos> previewOutline(PolyhedronParameters parameters, int maxBlocks) {
      return SphereGenerator.previewOutline(parameters, maxBlocks);
   }
}
