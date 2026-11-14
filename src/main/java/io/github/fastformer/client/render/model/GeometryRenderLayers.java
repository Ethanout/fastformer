package io.github.fastformer.client.render.model;

import java.util.Set;
import io.github.fastformer.common.world.BlockPositionSets;
import net.minecraft.core.BlockPos;

public record GeometryRenderLayers(Set<BlockPos> confirmed, Set<BlockPos> pending) {
   public GeometryRenderLayers {
      confirmed = BlockPositionSets.copyOf(confirmed);
      pending = BlockPositionSets.copyOf(pending);
   }

   public static GeometryRenderLayers empty() {
      return new GeometryRenderLayers(Set.of(), Set.of());
   }
}
