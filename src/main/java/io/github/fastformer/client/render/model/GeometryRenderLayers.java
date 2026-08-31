package io.github.fastformer.client.render.model;

import java.util.Set;
import net.minecraft.core.BlockPos;

public record GeometryRenderLayers(Set<BlockPos> confirmed, Set<BlockPos> pending) {
   public GeometryRenderLayers {
      confirmed = Set.copyOf(confirmed);
      pending = Set.copyOf(pending);
   }

   public static GeometryRenderLayers empty() {
      return new GeometryRenderLayers(Set.of(), Set.of());
   }
}
