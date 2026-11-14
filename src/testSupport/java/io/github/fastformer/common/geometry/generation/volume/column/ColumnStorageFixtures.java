package io.github.fastformer.common.geometry.generation.volume.column;

import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Builds column storage through its actual package contract for independent volume oracles. */
public final class ColumnStorageFixtures {
   private ColumnStorageFixtures() {}

   public static Set<BlockPos> boundary(Set<BlockPos> solid, int axis) {
      return complete(volume(solid, axis).boundaryResult(Integer.MAX_VALUE, BlockGenerationObserver.NONE, false));
   }

   public static long fiveNeighborOpenings(Set<BlockPos> solid, int axis) {
      return volume(solid, axis).fiveNeighborOpenings();
   }

   public static Set<BlockPos> solid(Set<BlockPos> solid, int axis) {
      return complete(volume(solid, axis).solidResult(Integer.MAX_VALUE, BlockGenerationObserver.NONE));
   }

   public static boolean usesCompactStorage(Set<BlockPos> blocks) {
      return blocks instanceof ColumnBlockSet || blocks instanceof BoundaryColumnBlockSet;
   }

   public static boolean hasEnclosedAir(Set<BlockPos> core, Set<BlockPos> overlays, int axis) {
      ColumnVolume volume = new ColumnVolume(axis, BlockGenerationObserver.NONE);
      core.forEach(volume::addTarget);
      overlays.forEach(volume::addShell);
      return volume.hasEnclosedAir();
   }

   private static ColumnVolume volume(Set<BlockPos> solid, int axis) {
      ColumnVolume volume = new ColumnVolume(axis, BlockGenerationObserver.NONE);
      solid.forEach(volume::addTarget);
      if (volume.blockCount() != solid.size()) {
         throw new IllegalArgumentException("the supplied solid is not column-convex on axis " + axis);
      }
      return volume;
   }

   private static Set<BlockPos> complete(ColumnVolume.Result result) {
      if (!result.complete()) throw new IllegalStateException("fixture exceeded the integer output limit");
      return result.blocks();
   }
}
