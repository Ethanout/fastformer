package io.github.fastformer.client.render.model;

import java.util.Set;
import net.minecraft.core.BlockPos;

public record BuildingRenderLayers(Set<BlockPos> confirmedRenderBlocks, Set<BlockPos> pendingRenderBlocks, Set<BlockPos> allBlocks) {
   public BuildingRenderLayers {
      confirmedRenderBlocks = Set.copyOf(confirmedRenderBlocks);
      pendingRenderBlocks = Set.copyOf(pendingRenderBlocks);
      allBlocks = Set.copyOf(allBlocks);
   }

   public static BuildingRenderLayers empty() {
      return new BuildingRenderLayers(Set.of(), Set.of(), Set.of());
   }
}
