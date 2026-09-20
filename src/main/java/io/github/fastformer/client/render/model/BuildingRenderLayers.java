package io.github.fastformer.client.render.model;

import java.util.Set;
import io.github.fastformer.fastplace.geometry.BlockPositionSets;
import net.minecraft.core.BlockPos;

public record BuildingRenderLayers(Set<BlockPos> confirmedRenderBlocks, Set<BlockPos> pendingRenderBlocks, Set<BlockPos> allBlocks) {
   public BuildingRenderLayers {
      confirmedRenderBlocks = BlockPositionSets.copyOf(confirmedRenderBlocks);
      pendingRenderBlocks = BlockPositionSets.copyOf(pendingRenderBlocks);
      allBlocks = BlockPositionSets.copyOf(allBlocks);
   }

   public static BuildingRenderLayers empty() {
      return new BuildingRenderLayers(Set.of(), Set.of(), Set.of());
   }
}
