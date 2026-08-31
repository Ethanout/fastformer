package io.github.fastformer.client.render.model;

import java.util.List;
import net.minecraft.core.BlockPos;

public record BuildingPreviewKey(long stateVersion, List<BlockPos> points, boolean heightConfirmed, boolean modifierHeld) {
   public BuildingPreviewKey {
      points = List.copyOf(points);
   }
}
