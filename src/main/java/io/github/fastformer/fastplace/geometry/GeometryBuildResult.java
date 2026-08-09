package io.github.fastformer.fastplace.geometry;

import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

public record GeometryBuildResult(long scanCells, Supplier<Set<BlockPos>> blocks, Component blockedReason) {
   public static GeometryBuildResult ready(long scanCells, Supplier<Set<BlockPos>> blocks) {
      return new GeometryBuildResult(scanCells, blocks, null);
   }

   public static GeometryBuildResult blocked(Component reason) {
      return new GeometryBuildResult(0L, Set::of, reason);
   }

   public boolean ready() {
      return this.blockedReason == null;
   }
}
